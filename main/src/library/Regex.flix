/*
 *  Copyright 2023 Stephen Tetley
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


///
/// Basic support for regular expression matching on Strings.
///
/// This module uses the builtin Regex datatype representing a compiled regular expression and
/// alternative implementations of functions from `String.flix` using regular expressions.
///
/// Note - for regex matching all scanning is left-to-right and "chunked" by the matcher.
/// This differs from String.flix where scanning is character at a time and can be left-to-right
/// or right-to-left. Hence this module provides analogue but not exactly equivalent of functions:
/// e.g.
/// > String.indexOfLeft ~> Regex/Text.indexOfFirst
/// > String.indexOfRight ~> Regex/Text.indexOfLast
/// > String.breakOnLeft ~> Regex/Text.breakOnFirst
/// > String.breakOnRight ~> Regex/Text.breakOnLast
///

mod Regex {

    use Regex.Flag
    use Regex.Flag.{CanonEq, CaseInsenstive,
        Comments, Dotall, Literal, Multiline,
        UnicodeCase, UnicodeCharacterClass, UnixLines
    }
    use Regex.Matcher
    use Regex.Matcher.{Matcher}


    ///
    /// Represents a flag controlling the compilation of regular expression.
    ///
    pub enum Flag with Eq, Order, ToString {
        case CanonEq
        case CaseInsenstive
        case Comments
        case Dotall
        case Literal
        case Multiline
        case UnicodeCase
        case UnicodeCharacterClass
        case UnixLines
    }

    ///
    /// Returns the int value of `flag`.
    ///
    /// Regex Pattern Flags predate `Enum` in Java so they are represented as an `Int32`
    /// and can be summed to make a bit mask.
    ///
    def toInt(flag: Flag): Int32 =
        import static get java.util.regex.Pattern.CANON_EQ: Int32 \ {} as get_CANON_EQ;
        import static get java.util.regex.Pattern.CASE_INSENSITIVE: Int32 \ {} as get_CASE_INSENSITIVE;
        import static get java.util.regex.Pattern.COMMENTS: Int32 \ {} as get_COMMENTS;
        import static get java.util.regex.Pattern.DOTALL: Int32 \ {} as get_DOTALL;
        import static get java.util.regex.Pattern.LITERAL: Int32 \ {} as get_LITERAL;
        import static get java.util.regex.Pattern.MULTILINE: Int32 \ {} as get_MULTILINE;
        import static get java.util.regex.Pattern.UNICODE_CASE: Int32 \ {} as get_UNICODE_CASE;
        import static get java.util.regex.Pattern.UNICODE_CHARACTER_CLASS: Int32 \ {} as get_UNICODE_CHARACTER_CLASS;
        import static get java.util.regex.Pattern.UNIX_LINES: Int32 \ {} as get_UNIX_LINES;
        match flag {
            case CanonEq                => get_CANON_EQ()
            case CaseInsenstive         => get_CASE_INSENSITIVE()
            case Comments               => get_COMMENTS()
            case Dotall                 => get_DOTALL()
            case Literal                => get_LITERAL()
            case Multiline              => get_MULTILINE()
            case UnicodeCase            => get_UNICODE_CASE()
            case UnicodeCharacterClass  => get_UNICODE_CHARACTER_CLASS()
            case UnixLines              => get_UNIX_LINES()
        }

    ///
    /// Sum a list of flags to an `Int32` bit mask.
    ///
    pub def sumFlags(flags: Set[Flag]): Int32 =
        Set.foldLeft((ac,e) -> ac + toInt(e), 0, flags)

    ///
    /// Decompose an Int32 representing bit masks into a list of flags.
    ///
    def listFlags(x: Int32): List[Flag] =
        let check = y -> {
            let y1 = toInt(y);
            (x &&& y1) == y1
        };
        List.filter(check, CanonEq :: CaseInsenstive :: Comments :: Dotall :: Literal ::
                            Multiline :: UnicodeCase :: UnicodeCharacterClass :: UnixLines :: Nil)

    ///
    /// Return the unmatchable Regex - a regular expression that will not match any input.
    ///
    pub def unmatchable(): Regex =
        try {
            import static java.util.regex.Pattern.compile(String): ##java.util.regex.Pattern \ {};
            compile("^\\b$")
        } catch {
            case _: ##java.lang.Exception => unreachable!()
        }

    ///
    /// Return the regular expression string that matches the the literal string `s`.
    ///
    pub def quote(s: String): String =
        import static java.util.regex.Pattern.quote(String): String \ {};
        quote(s)

    ///
    /// Return the regular expression string used to build this Regex.
    ///
    pub def pattern(rgx: Regex): String =
        import java.util.regex.Pattern.pattern(): String \ {};
        pattern(rgx)

    ///
    /// Return the flags used to build the Regex `rgx`.
    ///
    pub def flags(rgx: Regex): Set[Flag] =
        import java.util.regex.Pattern.flags(): Int32 \ {};
        let i = flags(rgx);
        listFlags(i) |> List.toSet


    ///
    /// Returns `true` if the entire string `s` is matched by the Regex `rgx`.
    ///
    pub def isMatch(rgx: Regex, s: String): Bool = region rc {
        import java.util.regex.Matcher.matches(): Bool \ rc;
        let Matcher(m1) = newMatcher(rc, rgx, s);
        matches(m1)
    }

    ///
    /// Returns `true` if the string `input` is matched by the Regex `rgx`
    /// at any position within the string `s`.
    ///
    pub def isSubmatch(rgx: Regex, s: String): Bool = region rc {
        newMatcher(rc, rgx, s) |> find!
    }

    ///
    /// Returns the positions of the all the occurrences of `substr` in `s`.
    ///
    /// Returns `Nil` if `substr` is the empty string.
    ///
    pub def indices(substr: {substr = Regex}, s: String): List[Int32] = region rc {
        let m = newMatcher(rc, substr.substr, s);
        match foldSubmatches(Chain.snoc, Chain.empty(), matcherStart, m) {
            case Ok(c)  => Chain.toList(c)
            case Err(_) => Nil
        }
    }

    ///
    /// Returns the contents of the all the occurrences of `substr` in `s`.
    ///
    /// Returns `Nil` if `substr` is the empty string.
    ///
    pub def submatches(substr: {substr = Regex}, s: String): List[String] = region rc {
        let m = newMatcher(rc, substr.substr, s);
        match foldSubmatches(Chain.snoc, Chain.empty(), matcherContent, m) {
            case Ok(c)  => Chain.toList(c)
            case Err(_) => Nil
        }
    }

    ///
    ///
    /// Count the occurrences of `substr` in string `s`.
    ///
    pub def countSubmatches(substr: {substr = Regex}, s: String): Int32 = region rc {
        let m = newMatcher(rc, substr.substr, s);
        match foldSubmatches((acc, _) -> acc + 1, 0, matcherStart, m) {
            case Ok(n)  => n
            case Err(_) => 0
        }
    }

    ///
    /// Splits the string `s` around matches of the Regex `regex`.
    ///
    pub def split(regex: {regex = Regex}, s: String): List[String] = region rc {
        import java.util.regex.Pattern.split(##java.lang.CharSequence): Array[String, rc] \ Write(rc);
        let _ = rc; // This avoids a redundancy error.
        Array.toList(split(regex.regex, checked_cast(s)))
    }


    ///
    /// Returns string `s` with every match of the Regex `from` replaced by the string `to`.
    ///
    pub def replace(from: {from = Regex}, to: {to = String}, s: String): String = region rc {
        import java.util.regex.Matcher.replaceAll(String): String \ rc;
        let Matcher(m1) = newMatcher(rc, from.from, s);
        replaceAll(m1, to.to)
    }

    ///
    /// Returns string `s` with the first match of the regular expression `from` replaced by the string `to`.
    ///
    pub def replaceFirst(from: {from = Regex}, to: {to = String}, s: String): String = region rc {
        import java.util.regex.Matcher.replaceFirst(String): String \ rc;
        let Matcher(m1) = newMatcher(rc, from.from, s);
        replaceFirst(m1, to.to)
    }

    ///
    /// Returns `true` if the string `s` starts the Regex `prefix`.
    ///
    pub def startsWith(prefix: {prefix = Regex}, s: String): Bool = region rc {
        import java.util.regex.Matcher.lookingAt(): Bool \ rc;
        let Matcher(m1) = newMatcher(rc, prefix.prefix, s);
        lookingAt(m1)
    }

    ///
    /// Returns `true` if the string `input` ends the regular expression Regex `suffix`.
    ///
    /// This will be slower than `startsWith` because there is no primitive Java function
    /// to call, instead the matches of `patt` are iterated until the last one is found.
    ///
    pub def endsWith(suffix: {suffix = Regex}, s: String): Bool = region rc {
        let m = newMatcher(rc, suffix.suffix, s);
        match lastSubmatch(matcherRange, m) {
            case Err(_)  => false
            case Ok(rng) => String.length(s) == rng.end
        }
    }

    ///
    /// Returns `Some(suffix)` of string `s` if its prefix matches `substr`.
    ///
    pub def stripPrefix(substr: {substr = Regex}, s: String): Option[String] = region rc {
        let m = newMatcher(rc, substr.substr, s);
        match firstSubmatch(matcherRange, m) {
            case Err(_) => None
            case Ok(r)  => if (r.start == 0) Some(String.dropLeft(r.end, s)) else None
        }
    }

    ///
    /// Returns `Some(prefix)` of string `s` if its suffix matches `substr`.
    ///
    pub def stripSuffix(substr: {substr = Regex}, s: String): Option[String] = region rc {
        let m = newMatcher(rc, substr.substr, s);
        match lastSubmatch(matcherRange, m) {
            case Err(_) => None
            case Ok(r)  => if (r.end == String.length(s)) Some(String.takeLeft(r.start, s)) else None
        }
    }

    ///
    /// Return the index of the first occurence of `substr` in `s` from the left.
    ///
    /// If the Regex `substr` is not present in `s` return None.
    ///
    pub def indexOfFirst(substr: {substr = Regex}, s: String): Option[Int32] = region rc {
        let m = newMatcher(rc, substr.substr, s);
        firstSubmatch(matcherStart, m) |> Result.toOption
    }

    ///
    /// Return the index of the last occurence of `substr` in `s` starting from the left.
    ///
    /// If the Regex `substr` is not present in `s` return None.
    ///
    pub def indexOfLast(substr: {substr = Regex}, s: String): Option[Int32] = region rc {
        let m = newMatcher(rc, substr.substr, s);
        lastSubmatch(matcherStart, m) |> Result.toOption
    }

    ///
    /// This is `indexOfFirst` with a start offset.
    ///
    /// If the Regex `substr` is not present in `s` return None.
    ///
    pub def indexOfFirstWithOffset(substr: {substr = Regex}, offset: {offset = Int32}, s: String): Option[Int32] = region rc {
        let m = newMatcher(rc, substr.substr, s);
        match setBounds!(start = offset.offset, end = String.length(s), m) {
            case Ok()   => firstSubmatch(matcherStart, m) |> Result.toOption
            case Err(_) => None
        }
    }

    ///
    /// This is `indexOfLast` with a start offset.
    ///
    /// If the Regex `substr` is not present in `s` return None.
    ///
    pub def indexOfLastWithOffset(substr: {substr = Regex}, offset: {offset = Int32}, s: String): Option[Int32] = region rc {
        let m = newMatcher(rc, substr.substr, s);
        match setBounds!(start = offset.offset, end = String.length(s), m) {
            case Ok()   => lastSubmatch(matcherStart, m) |> Result.toOption
            case Err(_) => None
        }
    }

    ///
    /// Find the first instance of Regex `substr` in string `s`, return a pair of the
    /// prefix of string `s` up to `sub` and the rest of string `s` including `sub`.
    ///
    pub def breakOnFirst(substr: {substr = Regex}, s: String): (String, String) = region rc {
        let m = newMatcher(rc, substr.substr, s);
        match firstSubmatch(matcherRange, m) {
            case Err(_) => (s, "")
            case Ok(r)  => (String.sliceLeft(end = r.start, s), String.sliceRight(start = r.start, s))
        }
    }

    ///
    /// Find the first instance of Regex `substr` in string `s`, return a pair of the
    /// prefix of string `s` up to and including `sub` and the rest of string `s` after `sub`.
    ///
    pub def breakAfterFirst(substr: {substr = Regex}, s: String): (String, String) = region rc {
        let m = newMatcher(rc, substr.substr, s);
        match firstSubmatch(matcherRange, m) {
            case Err(_) => (s, "")
            case Ok(r)  => (String.sliceLeft(end = r.end, s), String.sliceRight(start = r.end, s))
        }
    }

    ///
    /// Find the last instance of `substr` in string `s`, return a pair of the
    /// initial string including `substr` and suffix from `substr`.
    ///
    pub def breakOnLast(substr: {substr = Regex}, s: String): (String, String) = region rc {
        let m = newMatcher(rc, substr.substr, s);
        match lastSubmatch(matcherRange, m) {
            case Err(_) => (s, "")
            case Ok(r)  => (String.sliceLeft(end = r.end, s), String.sliceRight(start = r.end, s))
        }
    }

    ///
    /// Find the last instance of `substr` in string `s`, return a pair of the
    /// initial string including `substr` and suffix from `substr`.
    ///
    pub def breakBeforeLast(substr: {substr = Regex}, s: String): (String, String) = region rc {
        let m = newMatcher(rc, substr.substr, s);
        match lastSubmatch(matcherRange, m) {
            case Err(_) => (s, "")
            case Ok(r)  => (String.sliceLeft(end = r.start, s), String.sliceRight(start = r.start, s))
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Internal support for matching using ####java.util.regex.Matcher       //
    ///////////////////////////////////////////////////////////////////////////


    ///
    /// Matcher is imperative - it can be seen a a stepper through a stream
    /// of matches. The matcher is updated with the function find! to move to
    /// the next match.
    ///
    enum Matcher[_: Region](##java.util.regex.Matcher)

    ///
    /// Create a Matcher for Regex `rgx` on the source String `input`.
    ///
    def newMatcher(_: Region[r], rgx: Regex, s: String): Matcher[r] \ Write(r) =
        import java.util.regex.Pattern.matcher(##java.lang.CharSequence): ##java.util.regex.Matcher \ r;
        Matcher(matcher(rgx, checked_cast(s)))

    ///
    /// Attempt to find the next match. Returns `true` and moves to the next match
    /// if there is a next match otherwise returns `false`.
    ///
    /// The internal state of the matcher is updated.
    ///
    def find!(m: Matcher[r]): Bool \ Write(r) =
        import java.util.regex.Matcher.find(): Bool \ r;
        let Matcher(m1) = m;
        find(m1)

    ///
    /// Attempt to find the next match after the supplied position `pos`.
    /// Returns `true` and moves to the next match if there is a next match
    /// otherwise returns `false`.
    ///
    /// The internal state of the matcher is updated.
    ///
    def findFrom!(pos: Int32, m: Matcher[r]): Bool \ Write(r) =
        Result.tryCatch(_ -> {
            import java.util.regex.Matcher.find(Int32): Bool \ r;
            let Matcher(m1) = m;
            find(m1, pos)
        }) |> Result.getWithDefault(false)


    ///
    /// Attempt to find the last match by iterating through the input string.
    ///
    /// Returns `true` and moves the matcher to the start of the last match if there are
    /// one-or-more matches otherwise returns `false`.
    ///
    /// The internal state of the matcher is updated.
    ///
    /// Note - there are no primitive Java functions for right-to-left scanning provided
    /// by Java's JDK so the performance of `findLast!` is disadvantaged compared to `find!`.
    ///
    def findLast!(m: Matcher[r]): Bool \ Write(r) =
        def loop(lastPos) = {
            match find!(m) {
                case true  => {let pos1 = matcherStart(m); loop(pos1)}
                case false => lastPos
            }
        };
        match find!(m) {
            case false  => false
            case true   => {
                let pos = matcherStart(m);
                match loop(pos) {
                    case Err(_)          => false
                    case Ok(startOfLast) => findFrom!(startOfLast, m)
                }
            }
        }

    ///
    /// Set the bounds of the matcher's region.
    ///
    def setBounds!(start: {start = Int32}, end: {end = Int32}, m: Matcher[r]): Result[String, Unit] \ Write(r) =
        Result.tryCatch(_ -> {
            import java.util.regex.Matcher.region(Int32, Int32): ##java.util.regex.Matcher \ r as region1;
            let Matcher(m1) = m;
            discard region1(m1, start.start, end.end)
        })


    ///
    /// Return the start position of the current match of Matcher `m`.
    ///
    def matcherStart(m: Matcher[r]): Result[String, Int32] \ Read(r) =
        Result.tryCatch(_ -> {
            import java.util.regex.Matcher.start(): Int32 \ r;
            let Matcher(m1) = m;
            start(m1)
        })

    ///
    /// Return the end position of the current match of Matcher `m`.
    ///
    def matcherEnd(m: Matcher[r]): Result[String, Int32] \ Read(r) =
        Result.tryCatch(_ -> {
            import java.util.regex.Matcher.end(): Int32 \ r;
            let Matcher(m1) = m;
            end(m1)
        })

    ///
    /// Return the start and end positions of the matcher `m`.
    ///
    def matcherRange(m: Matcher[r]): Result[String, {start = Int32, end = Int32}] \ Read(r) =
        forM (s <- matcherStart(m);
              e <- matcherEnd(m))
        yield ({start = s, end = e})


    ///
    /// Return the text content of the current match of matcher `m`.
    ///
    def matcherContent(m: Matcher[r]): Result[String, String] \ Read(r) =
        Result.tryCatch(_ -> {
            import java.util.regex.Matcher.group(): String \ r;
            let Matcher(m1) = m;
            group(m1)
        })

    ///
    /// `MatchQuery` is a read query applied to a the current match of a `Matcher`.
    ///
    pub type alias MatchQuery[a: Type, r: Region] = Matcher[r] -> Result[String, a] \ Read(r)

    ///
    /// Returns the first result found by applying the query `asks` to `m` going from left to right.
    ///
    /// If the query `asks` or no match is found `Err(_)` is returned.
    ///
    def firstSubmatch(asks: MatchQuery[a, r], m: Matcher[r]): Result[String, a] \ { Read(r), Write(r) } =
        if (find!(m))
            asks(m)
        else
            Err("No match")


    ///
    /// Returns the last result found by applying the query `asks` to `m` going from left to right.
    ///
    /// If the query `asks` or no match is found `Err(_)` is returned.
    ///
    def lastSubmatch(asks: MatchQuery[a, r], m: Matcher[r]): Result[String, a] \ { Read(r), Write(r) } =
        if (findLast!(m))
            asks(m)
        else
            Err("No match")

    ///
    /// Fold on all the submatches going from left to right.
    ///
    /// If the query `asks` fails further processing is stopped and `Err(_)` is returned.
    ///
    /// `f` is applied to the result of applying the query `asks` and the folds accumulating value.
    /// The initial accumulating value is `x`.
    ///
    def foldSubmatches(f: (b, a) -> b \ ef, x: b, asks: MatchQuery[a, r], m: Matcher[r]): Result[String, b] \ { Read(r), Write(r), ef } =
        def loop(acc) = {
            match find!(m) {
                case true  => match asks(m) {
                    case Ok(a)    => loop(f(acc, a))
                    case Err(msg) => Err(msg)
                }
                case false => Ok(acc)
            }
        };
        loop(x)

}
