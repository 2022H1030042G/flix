/*
 * Copyright 2023 Magnus Madsen
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
package ca.uwaterloo.flix.api.lsp.provider.completion

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.api.lsp.Index
import ca.uwaterloo.flix.language.ast.TypedAst

object ExprCompleter extends Completer {

  def getCompletions(context: CompletionContext)(implicit flix: Flix, index: Index, root: TypedAst.Root, delta: DeltaContext): Iterable[Completion] = {
    DefCompleter.getCompletions(context) ++
      FieldCompleter.getCompletions(context) ++
      KeywordExprCompleter.getCompletions(context) ++
      MatchCompleter.getCompletions(context) ++
      VarCompleter.getCompletions(context) ++
      SignatureCompleter.getCompletions(context) ++
      SnippetCompleter.getCompletions(context)
  }

}
