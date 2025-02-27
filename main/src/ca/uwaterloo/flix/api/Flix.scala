/*
 * Copyright 2015-2016 Magnus Madsen, Ming-Ho Yee
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.api

import ca.uwaterloo.flix.language.ast.Ast.Input
import ca.uwaterloo.flix.language.ast._
import ca.uwaterloo.flix.language.dbg.AstPrinter
import ca.uwaterloo.flix.language.fmt.FormatOptions
import ca.uwaterloo.flix.language.phase._
import ca.uwaterloo.flix.language.phase.jvm.JvmBackend
import ca.uwaterloo.flix.language.{CompilationMessage, GenSym}
import ca.uwaterloo.flix.runtime.CompilationResult
import ca.uwaterloo.flix.tools.Summary
import ca.uwaterloo.flix.util.Formatter.NoFormatter
import ca.uwaterloo.flix.util._
import ca.uwaterloo.flix.util.collection.{ListMap, MultiMap}

import java.nio.charset.Charset
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object Flix {
  /**
    * The reserved Flix delimiter.
    */
  val Delimiter: String = "$"

  /**
    * The file extension for intermediate representation files.
    */
  val IrFileExtension = "flixir"

  /**
    * The maximum width of the intermediate representation files.
    */
  val IrFileWidth = 80

  /**
    * The number of spaces per indentation in the intermediate representation files.
    */
  val IrFileIndentation = 4
}

/**
  * Main programmatic interface for Flix.
  */
class Flix {

  /**
    * A sequence of inputs to be parsed into Flix ASTs.
    */
  private val inputs = mutable.Map.empty[String, Input]

  /**
    * The set of sources changed since last compilation.
    */
  private var changeSet: ChangeSet = ChangeSet.Everything

  /**
    * A cache of ASTs for incremental compilation.
    */
  private var cachedParsedAst: ParsedAst.Root = ParsedAst.Root(Map.empty, None, MultiMap.empty)
  private var cachedWeededAst: WeededAst.Root = WeededAst.Root(Map.empty, None, MultiMap.empty)
  private var cachedKindedAst: KindedAst.Root = KindedAst.Root(Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, None, Map.empty, MultiMap.empty)
  private var cachedResolvedAst: ResolvedAst.Root = ResolvedAst.Root(Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, List.empty, None, Map.empty, MultiMap.empty)
  private var cachedTypedAst: TypedAst.Root = TypedAst.Root(Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, None, Map.empty, Map.empty, ListMap.empty, MultiMap.empty)

  /**
    * A cache of ASTs for debugging.
    */
  private var cachedLiftedAst: LiftedAst.Root = LiftedAst.Root(Map.empty, Map.empty, None, Map.empty)
  private var cachedErasedAst: ErasedAst.Root = ErasedAst.Root(Map.empty, Map.empty, None, Map.empty, Set.empty, Set.empty)

  /**
    * Returns the cached LiftedAST.
    */
  def getLiftedAst: LiftedAst.Root = cachedLiftedAst

  /**
    * A sequence of internal inputs to be parsed into Flix ASTs.
    *
    * The core library *must* be present for any program to compile.
    */
  private val coreLibrary = List(
    // Prelude
    "Prelude.flix" -> LocalResource.get("/src/library/Prelude.flix"),

    // Comparison
    "Comparison.flix" -> LocalResource.get("/src/library/Comparison.flix"),

    // Operators
    "Neg.flix" -> LocalResource.get("/src/library/Neg.flix"),
    "Add.flix" -> LocalResource.get("/src/library/Add.flix"),
    "Sub.flix" -> LocalResource.get("/src/library/Sub.flix"),
    "Mul.flix" -> LocalResource.get("/src/library/Mul.flix"),
    "Div.flix" -> LocalResource.get("/src/library/Div.flix"),
    "Exp.flix" -> LocalResource.get("/src/library/Exp.flix"),
    "BitwiseNot.flix" -> LocalResource.get("/src/library/BitwiseNot.flix"),
    "BitwiseAnd.flix" -> LocalResource.get("/src/library/BitwiseAnd.flix"),
    "BitwiseOr.flix" -> LocalResource.get("/src/library/BitwiseOr.flix"),
    "BitwiseXor.flix" -> LocalResource.get("/src/library/BitwiseXor.flix"),
    "Bool.flix" -> LocalResource.get("/src/library/Bool.flix"),

    // Channels and Threads
    "Channel.flix" -> LocalResource.get("/src/library/Channel.flix"),
    "Thread.flix" -> LocalResource.get("/src/library/Thread.flix"),
    "Time.flix" -> LocalResource.get("/src/library/Time.flix"),

    // Built-in
    "Eq.flix" -> LocalResource.get("/src/library/Eq.flix"),
    "Hash.flix" -> LocalResource.get("/src/library/Hash.flix"),
    "Sendable.flix" -> LocalResource.get("/src/library/Sendable.flix"),
    "Order.flix" -> LocalResource.get("/src/library/Order.flix"),

    // Lattices
    "PartialOrder.flix" -> LocalResource.get("/src/library/PartialOrder.flix"),
    "LowerBound.flix" -> LocalResource.get("/src/library/LowerBound.flix"),
    "UpperBound.flix" -> LocalResource.get("/src/library/UpperBound.flix"),
    "JoinLattice.flix" -> LocalResource.get("/src/library/JoinLattice.flix"),
    "MeetLattice.flix" -> LocalResource.get("/src/library/MeetLattice.flix"),

    // String
    "ToString.flix" -> LocalResource.get("/src/library/ToString.flix"),

    // Reflect
    "Reflect.flix" -> LocalResource.get("/src/library/Reflect.flix"),

    // Debug
    "Debug.flix" -> LocalResource.get("/src/library/Debug.flix"),

    // References
    "Ref.flix" -> LocalResource.get("/src/library/Ref.flix"),
  )

  /**
    * A sequence of internal inputs to be parsed into Flix ASTs.
    *
    * The standard library is not required to be present for at least some programs to compile.
    */
  private val standardLibrary = List(
    "Array.flix" -> LocalResource.get("/src/library/Array.flix"),
    "Assert.flix" -> LocalResource.get("/src/library/Assert.flix"),
    "Benchmark.flix" -> LocalResource.get("/src/library/Benchmark.flix"),
    "BigDecimal.flix" -> LocalResource.get("/src/library/BigDecimal.flix"),
    "BigInt.flix" -> LocalResource.get("/src/library/BigInt.flix"),
    "Boxable.flix" -> LocalResource.get("/src/library/Boxable.flix"),
    "Boxed.flix" -> LocalResource.get("/src/library/Boxed.flix"),
    "Chain.flix" -> LocalResource.get("/src/library/Chain.flix"),
    "Char.flix" -> LocalResource.get("/src/library/Char.flix"),
    "Choice.flix" -> LocalResource.get("/src/library/Choice.flix"),
    "Closeable.flix" -> LocalResource.get("/src/library/Closeable.flix"),
    "Console.flix" -> LocalResource.get("/src/library/Console.flix"),
    "DelayList.flix" -> LocalResource.get("/src/library/DelayList.flix"),
    "DelayMap.flix" -> LocalResource.get("/src/library/DelayMap.flix"),
    "Down.flix" -> LocalResource.get("/src/library/Down.flix"),
    "Float32.flix" -> LocalResource.get("/src/library/Float32.flix"),
    "Float64.flix" -> LocalResource.get("/src/library/Float64.flix"),
    "Int8.flix" -> LocalResource.get("/src/library/Int8.flix"),
    "Int16.flix" -> LocalResource.get("/src/library/Int16.flix"),
    "Int32.flix" -> LocalResource.get("/src/library/Int32.flix"),
    "Int64.flix" -> LocalResource.get("/src/library/Int64.flix"),
    "Iterable.flix" -> LocalResource.get("/src/library/Iterable.flix"),
    "Iterator.flix" -> LocalResource.get("/src/library/Iterator.flix"),
    "List.flix" -> LocalResource.get("/src/library/List.flix"),
    "Map.flix" -> LocalResource.get("/src/library/Map.flix"),
    "Nec.flix" -> LocalResource.get("/src/library/Nec.flix"),
    "Nel.flix" -> LocalResource.get("/src/library/Nel.flix"),
    "Object.flix" -> LocalResource.get("/src/library/Object.flix"),
    "Option.flix" -> LocalResource.get("/src/library/Option.flix"),
    "Random.flix" -> LocalResource.get("/src/library/Random.flix"),
    "Region.flix" -> LocalResource.get("/src/library/Region.flix"),
    "Result.flix" -> LocalResource.get("/src/library/Result.flix"),
    "Set.flix" -> LocalResource.get("/src/library/Set.flix"),
    "String.flix" -> LocalResource.get("/src/library/String.flix"),
    "System.flix" -> LocalResource.get("/src/library/System.flix"),
    "MultiMap.flix" -> LocalResource.get("/src/library/MultiMap.flix"),

    "MutDeque.flix" -> LocalResource.get("/src/library/MutDeque.flix"),
    "MutList.flix" -> LocalResource.get("/src/library/MutList.flix"),
    "MutSet.flix" -> LocalResource.get("/src/library/MutSet.flix"),
    "MutMap.flix" -> LocalResource.get("/src/library/MutMap.flix"),

    "File.flix" -> LocalResource.get("/src/library/File.flix"),

    "Environment.flix" -> LocalResource.get("/src/library/Environment.flix"),

    "Applicative.flix" -> LocalResource.get("/src/library/Applicative.flix"),
    "CommutativeGroup.flix" -> LocalResource.get("/src/library/CommutativeGroup.flix"),
    "CommutativeMonoid.flix" -> LocalResource.get("/src/library/CommutativeMonoid.flix"),
    "CommutativeSemiGroup.flix" -> LocalResource.get("/src/library/CommutativeSemiGroup.flix"),
    "Foldable.flix" -> LocalResource.get("/src/library/Foldable.flix"),
    "FromString.flix" -> LocalResource.get("/src/library/FromString.flix"),
    "Functor.flix" -> LocalResource.get("/src/library/Functor.flix"),
    "Filterable.flix" -> LocalResource.get("/src/library/Filterable.flix"),
    "Group.flix" -> LocalResource.get("/src/library/Group.flix"),
    "Identity.flix" -> LocalResource.get("/src/library/Identity.flix"),
    "Monad.flix" -> LocalResource.get("/src/library/Monad.flix"),
    "MonadZero.flix" -> LocalResource.get("/src/library/MonadZero.flix"),
    "MonadZip.flix" -> LocalResource.get("/src/library/MonadZip.flix"),
    "Monoid.flix" -> LocalResource.get("/src/library/Monoid.flix"),
    "Reducible.flix" -> LocalResource.get("/src/library/Reducible.flix"),
    "SemiGroup.flix" -> LocalResource.get("/src/library/SemiGroup.flix"),
    "Traversable.flix" -> LocalResource.get("/src/library/Traversable.flix"),
    "Witherable.flix" -> LocalResource.get("/src/library/Witherable.flix"),
    "UnorderedFoldable.flix" -> LocalResource.get("/src/library/UnorderedFoldable.flix"),
    "Collectable.flix" -> LocalResource.get("/src/library/Collectable.flix"),

    "Validation.flix" -> LocalResource.get("/src/library/Validation.flix"),

    "StringBuilder.flix" -> LocalResource.get("/src/library/StringBuilder.flix"),
    "RedBlackTree.flix" -> LocalResource.get("/src/library/RedBlackTree.flix"),
    "GetOpt.flix" -> LocalResource.get("/src/library/GetOpt.flix"),

    "Concurrent/Channel.flix" -> LocalResource.get("/src/library/Concurrent/Channel.flix"),
    "Concurrent/Condition.flix" -> LocalResource.get("/src/library/Concurrent/Condition.flix"),
    "Concurrent/CyclicBarrier.flix" -> LocalResource.get("/src/library/Concurrent/CyclicBarrier.flix"),
    "Concurrent/ReentrantLock.flix" -> LocalResource.get("/src/library/Concurrent/ReentrantLock.flix"),

    "Time/Duration.flix" -> LocalResource.get("/src/library/Time/Duration.flix"),
    "Time/Epoch.flix" -> LocalResource.get("/src/library/Time/Epoch.flix"),
    "Time/Instant.flix" -> LocalResource.get("/src/library/Time/Instant.flix"),

    "Fixpoint/Compiler.flix" -> LocalResource.get("/src/library/Fixpoint/Compiler.flix"),
    "Fixpoint/Debugging.flix" -> LocalResource.get("/src/library/Fixpoint/Debugging.flix"),
    "Fixpoint/IndexSelection.flix" -> LocalResource.get("/src/library/Fixpoint/IndexSelection.flix"),
    "Fixpoint/Interpreter.flix" -> LocalResource.get("/src/library/Fixpoint/Interpreter.flix"),
    "Fixpoint/Options.flix" -> LocalResource.get("/src/library/Fixpoint/Options.flix"),
    "Fixpoint/PredSymsOf.flix" -> LocalResource.get("/src/library/Fixpoint/PredSymsOf.flix"),
    "Fixpoint/Simplifier.flix" -> LocalResource.get("/src/library/Fixpoint/Simplifier.flix"),
    "Fixpoint/Solver.flix" -> LocalResource.get("/src/library/Fixpoint/Solver.flix"),
    "Fixpoint/Stratifier.flix" -> LocalResource.get("/src/library/Fixpoint/Stratifier.flix"),
    "Fixpoint/SubstitutePredSym.flix" -> LocalResource.get("/src/library/Fixpoint/SubstitutePredSym.flix"),
    "Fixpoint/VarsToIndices.flix" -> LocalResource.get("/src/library/Fixpoint/VarsToIndices.flix"),

    "Fixpoint/Ast/BodyPredicate.flix" -> LocalResource.get("/src/library/Fixpoint/Ast/BodyPredicate.flix"),
    "Fixpoint/Ast/BodyTerm.flix" -> LocalResource.get("/src/library/Fixpoint/Ast/BodyTerm.flix"),
    "Fixpoint/Ast/Constraint.flix" -> LocalResource.get("/src/library/Fixpoint/Ast/Constraint.flix"),
    "Fixpoint/Ast/Datalog.flix" -> LocalResource.get("/src/library/Fixpoint/Ast/Datalog.flix"),
    "Fixpoint/Ast/Denotation.flix" -> LocalResource.get("/src/library/Fixpoint/Ast/Denotation.flix"),
    "Fixpoint/Ast/Fixity.flix" -> LocalResource.get("/src/library/Fixpoint/Ast/Fixity.flix"),
    "Fixpoint/Ast/HeadPredicate.flix" -> LocalResource.get("/src/library/Fixpoint/Ast/HeadPredicate.flix"),
    "Fixpoint/Ast/HeadTerm.flix" -> LocalResource.get("/src/library/Fixpoint/Ast/HeadTerm.flix"),
    "Fixpoint/Ast/Polarity.flix" -> LocalResource.get("/src/library/Fixpoint/Ast/Polarity.flix"),
    "Fixpoint/Ast/PrecedenceGraph.flix" -> LocalResource.get("/src/library/Fixpoint/Ast/PrecedenceGraph.flix"),
    "Fixpoint/Ast/VarSym.flix" -> LocalResource.get("/src/library/Fixpoint/Ast/VarSym.flix"),

    "Fixpoint/Ram/BoolExp.flix" -> LocalResource.get("/src/library/Fixpoint/Ram/BoolExp.flix"),
    "Fixpoint/Ram/RamStmt.flix" -> LocalResource.get("/src/library/Fixpoint/Ram/RamStmt.flix"),
    "Fixpoint/Ram/RamSym.flix" -> LocalResource.get("/src/library/Fixpoint/Ram/RamSym.flix"),
    "Fixpoint/Ram/RamTerm.flix" -> LocalResource.get("/src/library/Fixpoint/Ram/RamTerm.flix"),
    "Fixpoint/Ram/RelOp.flix" -> LocalResource.get("/src/library/Fixpoint/Ram/RelOp.flix"),
    "Fixpoint/Ram/RowVar.flix" -> LocalResource.get("/src/library/Fixpoint/Ram/RowVar.flix"),

    "Fixpoint/Shared/PredSym.flix" -> LocalResource.get("/src/library/Fixpoint/Shared/PredSym.flix"),

    "Graph.flix" -> LocalResource.get("/src/library/Graph.flix"),
    "Vector.flix" -> LocalResource.get("/src/library/Vector.flix"),
    "Regex.flix" -> LocalResource.get("/src/library/Regex.flix"),
  )

  /**
    * A map to track the time spent in each phase and sub-phase.
    */
  var phaseTimers: ListBuffer[PhaseTime] = ListBuffer.empty

  /**
    * The current phase we are in. Initially null.
    */
  private var currentPhase: PhaseTime = _

  /**
    * The progress bar.
    */
  private val progressBar: ProgressBar = new ProgressBar

  /**
    * The default assumed charset.
    */
  val defaultCharset: Charset = Charset.forName("UTF-8")

  /**
    * The current Flix options.
    */
  var options: Options = Options.Default

  /**
    * The fork join pool for `this` Flix instance.
    */
  private var forkJoinPool: java.util.concurrent.ForkJoinPool = _

  /**
    * The fork join task support for `this` Flix instance.
    */
  var forkJoinTaskSupport: scala.collection.parallel.ForkJoinTaskSupport = _

  /**
    * The symbol generator associated with this Flix instance.
    */
  val genSym = new GenSym()

  /**
    * The default output formatter.
    */
  private var formatter: Formatter = NoFormatter

  /**
    * A class loader for loading external JARs.
    */
  val jarLoader = new ExternalJarLoader

  /**
    * Adds the given string `text` with the given `name`.
    */
  def addSourceCode(name: String, text: String): Flix = {
    if (name == null)
      throw new IllegalArgumentException("'name' must be non-null.")
    if (text == null)
      throw new IllegalArgumentException("'text' must be non-null.")
    addInput(name, Input.Text(name, text, stable = false))
    this
  }

  /**
    * Removes the source code with the given `name`.
    */
  def remSourceCode(name: String): Flix = {
    if (name == null)
      throw new IllegalArgumentException("'name' must be non-null.")
    remInput(name, Input.Text(name, "", stable = false))
    this
  }

  /**
    * Adds the given path `p` as Flix source file.
    */
  def addFlix(p: Path): Flix = {
    if (p == null)
      throw new IllegalArgumentException(s"'p' must be non-null.")
    if (!Files.exists(p))
      throw new IllegalArgumentException(s"'$p' must be a file.")
    if (!Files.isRegularFile(p))
      throw new IllegalArgumentException(s"'$p' must be a regular file.")
    if (!Files.isReadable(p))
      throw new IllegalArgumentException(s"'$p' must be a readable file.")
    if (!p.getFileName.toString.endsWith(".flix"))
      throw new IllegalArgumentException(s"'$p' must be a *.flix file.")

    addInput(p.toString, Input.TxtFile(p))
    this
  }

  /**
    * Adds the given path `p` as a Flix package file.
    */
  def addPkg(p: Path): Flix = {
    if (p == null)
      throw new IllegalArgumentException(s"'p' must be non-null.")
    if (!Files.exists(p))
      throw new IllegalArgumentException(s"'$p' must be a file.")
    if (!Files.isRegularFile(p))
      throw new IllegalArgumentException(s"'$p' must be a regular file.")
    if (!Files.isReadable(p))
      throw new IllegalArgumentException(s"'$p' must be a readable file.")
    if (!p.getFileName.toString.endsWith(".fpkg"))
      throw new IllegalArgumentException(s"'$p' must be a *.pkg file.")

    addInput(p.toString, Input.PkgFile(p))
    this
  }

  /**
    * Removes the given path `p` as a Flix source file.
    */
  def remFlix(p: Path): Flix = {
    if (!p.getFileName.toString.endsWith(".flix"))
      throw new IllegalArgumentException(s"'$p' must be a *.flix file.")

    remInput(p.toString, Input.TxtFile(p))
    this
  }

  /**
    * Adds the JAR file at path `p` to the class loader.
    */
  def addJar(p: Path): Flix = {
    if (p == null)
      throw new IllegalArgumentException(s"'p' must be non-null.")
    if (!Files.exists(p))
      throw new IllegalArgumentException(s"'$p' must be a file.")
    if (!Files.isRegularFile(p))
      throw new IllegalArgumentException(s"'$p' must be a regular file.")
    if (!Files.isReadable(p))
      throw new IllegalArgumentException(s"'$p' must be a readable file.")

    jarLoader.addURL(p.toUri.toURL)
    this
  }

  /**
    * Adds the given `input` under the given `name`.
    */
  private def addInput(name: String, input: Input): Unit = inputs.get(name) match {
    case None =>
      inputs += name -> input
    case Some(_) =>
      changeSet = changeSet.markChanged(input)
      inputs += name -> input
  }

  /**
    * Removes the given `input` under the given `name`.
    *
    * Note: Removing an input means to replace it by the empty string.
    */
  private def remInput(name: String, input: Input): Unit = inputs.get(name) match {
    case None => // nop
    case Some(_) =>
      changeSet = changeSet.markChanged(input)
      inputs += name -> Input.Text(name, "", stable = false)
  }

  /**
    * Sets the options used for this Flix instance.
    */
  def setOptions(opts: Options): Flix = {
    if (opts == null)
      throw new IllegalArgumentException("'opts' must be non-null.")
    options = opts
    this
  }

  /**
    * Returns the format options associated with this Flix instance.
    */
  def getFormatOptions: FormatOptions = {
    FormatOptions(
      ignorePur = options.xnobooleffects,
      ignoreEff = options.xnoseteffects,
      varNames = FormatOptions.VarName.NameBased // TODO add cli option
    )
  }

  /**
    * Returns the current formatter instance.
    */
  def getFormatter: Formatter = this.formatter

  /**
    * Sets the output formatter used for this Flix instance.
    */
  def setFormatter(formatter: Formatter): Flix = {
    if (formatter == null)
      throw new IllegalArgumentException("'formatter' must be non-null.")
    this.formatter = formatter
    this
  }

  /**
    * Converts a list of compiler error messages to a list of printable messages.
    * Decides whether or not to print the explanation.
    */
  def mkMessages(errors: Seq[CompilationMessage]): List[String] = {
    if (options.explain)
      errors.sortBy(_.loc).map(cm => cm.message(formatter) + cm.explain(formatter).getOrElse("")).toList
    else
      errors.sortBy(_.loc).map(cm => cm.message(formatter)).toList
  }

  /**
    * Compiles the Flix program and returns a typed ast.
    */
  def check(): Validation[TypedAst.Root, CompilationMessage] = try {
    import Validation.Implicit.AsMonad

    // Mark this object as implicit.
    implicit val flix: Flix = this

    // Initialize fork join pool.
    initForkJoin()

    // Reset the phase information.
    phaseTimers = ListBuffer.empty

    // The default entry point
    val entryPoint = flix.options.entryPoint

    // The compiler pipeline.
    val result = for {
      afterReader <- Reader.run(getInputs)
      afterParser <- Parser.run(afterReader, entryPoint, cachedParsedAst, changeSet)
      afterWeeder <- Weeder.run(afterParser, cachedWeededAst, changeSet)
      afterNamer <- Namer.run(afterWeeder)
      afterResolver <- Resolver.run(afterNamer, cachedResolvedAst, changeSet)
      afterKinder <- Kinder.run(afterResolver, cachedKindedAst, changeSet)
      afterDeriver <- Deriver.run(afterKinder)
      afterTyper <- Typer.run(afterDeriver, cachedTypedAst, changeSet)
      afterEntryPoint <- EntryPoint.run(afterTyper)
      afterStatistics <- Statistics.run(afterEntryPoint)
      _ <- Instances.run(afterStatistics, cachedTypedAst, changeSet)
      afterStratifier <- Stratifier.run(afterStatistics)
      _ <- Regions.run(afterStratifier)
      afterPatMatch <- PatternExhaustiveness.run(afterStratifier)
      afterRedundancy <- Redundancy.run(afterPatMatch)
      afterSafety <- Safety.run(afterRedundancy)
    } yield {
      // Update caches for incremental compilation.
      if (options.incremental) {
        this.cachedParsedAst = afterParser
        this.cachedWeededAst = afterWeeder
        this.cachedKindedAst = afterKinder
        this.cachedResolvedAst = afterResolver
        this.cachedTypedAst = afterTyper
      }
      afterSafety
    }

    // Write formatted asts to disk based on options.
    // (Possible duplicate files in codeGen will just be empty and overwritten there)
    AstPrinter.printAsts()

    // Shutdown fork join pool.
    shutdownForkJoin()

    // Reset the progress bar.
    progressBar.complete()

    // Print summary?
    if (options.xsummary) {
      Summary.printSummary(result)
    }

    // Return the result (which could contain soft failures).
    result
  } catch {
    case ex: InternalCompilerException =>
      CrashHandler.handleCrash(ex)(this)
      throw ex
  }

  /**
    * Compiles the given typed ast to an executable ast.
    */
  def codeGen(typedAst: TypedAst.Root): Validation[CompilationResult, CompilationMessage] = try {
    // Mark this object as implicit.
    implicit val flix: Flix = this

    // Initialize fork join pool.
    initForkJoin()

    val afterDocumentor = Documentor.run(typedAst)
    val afterLowering = Lowering.run(afterDocumentor)
    val afterEarlyTreeShaker = EarlyTreeShaker.run(afterLowering)
    val afterMonomorph = Monomorph.run(afterEarlyTreeShaker)
    val afterSimplifier = Simplifier.run(afterMonomorph)
    val afterClosureConv = ClosureConv.run(afterSimplifier)
    cachedLiftedAst = LambdaLift.run(afterClosureConv)
    val afterTailrec = Tailrec.run(cachedLiftedAst)
    val afterOptimizer = Optimizer.run(afterTailrec)
    val afterLateTreeShaker = LateTreeShaker.run(afterOptimizer)
    val afterCallByValue = CallByValue.run(afterLateTreeShaker)
    val afterVarNumbering = VarNumbering.run(afterCallByValue)
    val afterFinalize = Finalize.run(afterVarNumbering)
    cachedErasedAst = Eraser.run(afterFinalize)
    val afterJvmBackend = JvmBackend.run(cachedErasedAst)
    val result = Finish.run(afterJvmBackend)

    // Write formatted asts to disk based on options.
    AstPrinter.printAsts()

    // Shutdown fork join pool.
    shutdownForkJoin()

    // Reset the progress bar.
    progressBar.complete()

    // Return the result.
    result
  } catch {
    case ex: InternalCompilerException =>
      CrashHandler.handleCrash(ex)(this)
      throw ex
  }

  /**
    * Compiles the given typed ast to an executable ast.
    */
  def compile(): Validation[CompilationResult, CompilationMessage] = {
    val result = check().toHardFailure
    Validation.flatMapN(result)(codeGen)
  }

  /**
    * Enters the phase with the given name.
    */
  def phase[A](phase: String)(f: => A): A = {
    // Initialize the phase time object.
    currentPhase = PhaseTime(phase, 0, Nil)

    if (options.progress) {
      progressBar.observe(currentPhase.phase, "", sample = false)
    }

    // Measure the execution time.
    val t = System.nanoTime()
    val r = f
    val e = System.nanoTime() - t

    // Update the phase time.
    currentPhase = currentPhase.copy(time = e)

    // And add it to the list of executed phases.
    phaseTimers += currentPhase

    // Print performance information if in verbose mode.
    if (options.debug) {
      // Print information about the phase.
      val d = new Duration(e)
      val emojiPart = formatter.blue("✓ ")
      val phasePart = formatter.blue(f"$phase%-40s")
      val timePart = f"${d.fmtMiliSeconds}%8s"
      Console.println(emojiPart + phasePart + timePart)

      // Print information about each subphase.
      for ((subphase, e) <- currentPhase.subphases.reverse) {
        val d = new Duration(e)
        val emojiPart = "    "
        val phasePart = formatter.magenta(f"$subphase%-37s")
        val timePart = f"(${d.fmtMiliSeconds}%8s)"
        Console.println(emojiPart + phasePart + timePart)
      }
    }

    // Return the result computed by the phase.
    r
  }

  /**
    * Enters the sub-phase with the given name.
    */
  def subphase[A](subphase: String)(f: => A): A = {
    // Measure the execution time.
    val t = System.nanoTime()
    val r = f
    val e = System.nanoTime() - t

    // Update the phase with information about the subphase.
    val subphases = (subphase, e) :: currentPhase.subphases
    currentPhase = currentPhase.copy(subphases = subphases)

    // Return the result computed by the subphase.
    r
  }

  /**
    * Returns the total compilation time in nanoseconds.
    */
  def getTotalTime: Long = phaseTimers.foldLeft(0L) {
    case (acc, phase) => acc + phase.time
  }

  /**
    * A callback to indicate that work has started on the given subtask.
    */
  def subtask(subtask: String, sample: Boolean = false): Unit = {
    if (options.progress) {
      progressBar.observe(currentPhase.phase, subtask, sample)
    }
  }

  /**
    * Returns a list of inputs constructed from the strings and paths passed to Flix.
    */
  private def getInputs: List[Input] = {
    val lib = options.lib match {
      case LibLevel.Nix => Nil
      case LibLevel.Min => getLibraryInputs(coreLibrary)
      case LibLevel.All => getLibraryInputs(coreLibrary ++ standardLibrary)
    }
    inputs.values.toList ::: lib
  }

  /**
    * Returns the inputs for the given list of (path, text) pairs.
    */
  private def getLibraryInputs(xs: List[(String, String)]): List[Input] = xs.foldLeft(List.empty[Input]) {
    case (xs, (name, text)) => Input.Text(name, text, stable = true) :: xs
  }

  /**
    * Initializes the fork join pools.
    */
  private def initForkJoin(): Unit = {
    forkJoinPool = new java.util.concurrent.ForkJoinPool(options.threads)
    forkJoinTaskSupport = new scala.collection.parallel.ForkJoinTaskSupport(forkJoinPool)
  }

  /**
    * Shuts down the fork join pools.
    */
  private def shutdownForkJoin(): Unit = {
    forkJoinPool.shutdown()
  }

}
