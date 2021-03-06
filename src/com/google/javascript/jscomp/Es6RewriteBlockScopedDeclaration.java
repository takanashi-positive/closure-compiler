/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.common.base.Predicate;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.Normalize.NormalizeStatements;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Rewrite "let"s and "const"s as "var"s. Rename block-scoped declarations and their references when
 * necessary.
 *
 * <p>Note that this must run after Es6RewriteDestructuring, since it does not process destructuring
 * let/const declarations at all.
 *
 * <p>TODO(moz): Try to use MakeDeclaredNamesUnique
 *
 * @author moz@google.com (Michael Zhou)
 */
public final class Es6RewriteBlockScopedDeclaration extends AbstractPostOrderCallback
    implements HotSwapCompilerPass {

  private static final Set<Token> LOOP_TOKENS =
      EnumSet.of(Token.WHILE, Token.FOR, Token.FOR_IN, Token.FOR_OF, Token.DO, Token.FUNCTION);

  private final AbstractCompiler compiler;
  private final Table<Node, String, String> renameTable = HashBasedTable.create();
  private final Set<Node> letConsts = new HashSet<>();
  private final Set<String> undeclaredNames = new HashSet<>();

  public Es6RewriteBlockScopedDeclaration(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (!n.hasChildren() || !NodeUtil.isBlockScopedDeclaration(n.getFirstChild())) {
      return;
    }

    Scope scope = t.getScope();
    Node nameNode = n.getFirstChild();
    if (!n.isClass() && !n.isFunction() && !nameNode.hasChildren()
        && (parent == null || !NodeUtil.isEnhancedFor(parent))
        && !n.isCatch()
        && inLoop(n)) {
      Node undefined = IR.name("undefined");
      if (nameNode.getJSDocInfo() != null || n.getJSDocInfo() != null) {
        JSDocInfoBuilder jsDoc = new JSDocInfoBuilder(false);
        jsDoc.recordType(new JSTypeExpression(new Node(Token.QMARK), n.getSourceFileName()));
        undefined = IR.cast(undefined, jsDoc.build());
      }
      undefined.useSourceInfoFromForTree(nameNode);
      nameNode.addChildToFront(undefined);
      compiler.reportChangeToEnclosingScope(undefined);
    }

    String oldName = nameNode.getString();
    if (n.isLet() || n.isConst()) {
      letConsts.add(n);
    }
    Scope hoistScope = scope.getClosestHoistScope();
    if (scope != hoistScope) {
      String newName = oldName;
      if (hoistScope.isDeclared(oldName, true) || undeclaredNames.contains(oldName)) {
        do {
          newName = oldName + "$" + compiler.getUniqueNameIdSupplier().get();
        } while (hoistScope.isDeclared(newName, true));
        nameNode.setString(newName);
        compiler.reportChangeToEnclosingScope(nameNode);
        Node scopeRoot = scope.getRootNode();
        renameTable.put(scopeRoot, oldName, newName);
      }
      Var oldVar = scope.getVar(oldName);
      scope.undeclare(oldVar);
      hoistScope.declare(newName, nameNode, oldVar.input);
    }
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverseEs6(compiler, root, new CollectUndeclaredNames());
    NodeTraversal.traverseEs6(compiler, root, this);
    // Needed for let / const declarations in .d.ts externs.
    TranspilationPasses.processTranspile(compiler, externs, this);
    NodeTraversal.traverseEs6(compiler, root, new Es6RenameReferences(renameTable));
    LoopClosureTransformer transformer = new LoopClosureTransformer();
    NodeTraversal.traverseEs6(compiler, root, transformer);
    transformer.transformLoopClosure();
    rewriteDeclsToVars();

    // Block scoped function declarations can occur in any language mode, however for
    // transpilation to ES3 and ES5, we want to hoist the functions from the block-scope by
    // redeclaring them in var assignments
    //
    // If block-scope-declared function is the only "ES6 feature" for which we want to transpile,
    // then the transpilation process will not rewriteFunctions. Thus, we manually check whether
    // we need to rewrite in that case.
    if (compiler.getOptions().needsTranspilationFrom(FeatureSet.ES6)) {
      RewriteBlockScopedFunctionDeclaration rewriteFunction =
          new RewriteBlockScopedFunctionDeclaration();

      for (Node singleRoot : root.children()) {
        FeatureSet features = (FeatureSet) singleRoot.getProp(Node.FEATURE_SET);
        if (features.has(Feature.BLOCK_SCOPED_FUNCTION_DECLARATION)) {
          NodeTraversal.traverseEs6(compiler, singleRoot, rewriteFunction);
        }
      }
    }
  }

  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    NodeTraversal.traverseEs6(compiler, scriptRoot, new CollectUndeclaredNames());
    NodeTraversal.traverseEs6(compiler, scriptRoot, this);
    NodeTraversal.traverseEs6(compiler, scriptRoot, new Es6RenameReferences(renameTable));
    LoopClosureTransformer transformer = new LoopClosureTransformer();
    NodeTraversal.traverseEs6(compiler, scriptRoot, transformer);
    transformer.transformLoopClosure();
    rewriteDeclsToVars();
    NodeTraversal.traverseEs6(compiler, scriptRoot, new RewriteBlockScopedFunctionDeclaration());
  }

  /**
   * Whether n is inside a loop. If n is inside a function which is inside a loop, we do not
   * consider it to be inside a loop.
   */
  private boolean inLoop(Node n) {
    Node enclosingNode = NodeUtil.getEnclosingNode(n, loopPredicate);
    return enclosingNode != null && !enclosingNode.isFunction();
  }

  private static final Predicate<Node> loopPredicate = new Predicate<Node>() {
    @Override
    public boolean apply(Node n) {
      return LOOP_TOKENS.contains(n.getToken());
    }
  };

  private static void extractInlineJSDoc(Node srcDeclaration, Node srcName, Node destDeclaration) {
    JSDocInfo existingInfo = srcDeclaration.getJSDocInfo();
    if (existingInfo == null) {
      // Extract inline JSDoc from "src" and add it to the "dest" node.
      existingInfo = srcName.getJSDocInfo();
      srcName.setJSDocInfo(null);
    }
    JSDocInfoBuilder builder = JSDocInfoBuilder.maybeCopyFrom(existingInfo);
    destDeclaration.setJSDocInfo(builder.build());
  }

  private static void maybeAddConstJSDoc(Node srcDeclaration, Node srcParent, Node srcName,
      Node destDeclaration) {
    if (srcDeclaration.isConst()
        // Don't add @const for the left side of a for/in. If we do we get warnings from the NTI.
        && !(srcParent.isForIn() && srcDeclaration == srcParent.getFirstChild())) {
      extractInlineJSDoc(srcDeclaration, srcName, destDeclaration);
      JSDocInfoBuilder builder = JSDocInfoBuilder.maybeCopyFrom(destDeclaration.getJSDocInfo());
      builder.recordConstancy();
      destDeclaration.setJSDocInfo(builder.build());
    }
  }

  private void handleDeclarationList(Node declarationList, Node parent) {
    // Normalize: "const i = 0, j = 0;" becomes "/** @const */ var i = 0; /** @const */ var j = 0;"
    while (declarationList.hasMoreThanOneChild()) {
      Node name = declarationList.getLastChild();
      Node newDeclaration = IR.var(name.detach()).useSourceInfoFrom(declarationList);
      maybeAddConstJSDoc(declarationList, parent, name, newDeclaration);
      parent.addChildAfter(newDeclaration, declarationList);
      compiler.reportChangeToEnclosingScope(parent);
    }
    maybeAddConstJSDoc(declarationList, parent, declarationList.getFirstChild(), declarationList);
    declarationList.setToken(Token.VAR);
  }

  private void addNodeBeforeLoop(Node newNode, Node loopNode) {
    Node insertSpot = loopNode;
    while (insertSpot.getParent().isLabel()) {
      insertSpot = insertSpot.getParent();
    }
    insertSpot.getParent().addChildBefore(newNode, insertSpot);
    compiler.reportChangeToEnclosingScope(newNode);
  }

  private void rewriteDeclsToVars() {
    if (!letConsts.isEmpty()) {
      for (Node n : letConsts) {
        if (n.isConst()) {
          handleDeclarationList(n, n.getParent());
        }
        n.setToken(Token.VAR);
        compiler.reportChangeToEnclosingScope(n);
      }
    }
  }

  private class RewriteBlockScopedFunctionDeclaration extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isFunction()) {
        NormalizeStatements.visitFunction(n, compiler);
      }
    }
  }

  /**
   * Records undeclared names and aggressively rename possible references to them.
   * Eg: In "{ let inner; } use(inner);", we rename the let declared variable.
   */
  private class CollectUndeclaredNames extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isName() && !t.getScope().isDeclared(n.getString(), true)) {
        undeclaredNames.add(n.getString());
      }
    }
  }

  /**
   * Transforms let/const declarations captured by loop closures.
   */
  private class LoopClosureTransformer extends AbstractPostOrderCallback {
    private static final String LOOP_OBJECT_NAME = "$jscomp$loop";

    private final Map<Node, LoopObject> loopObjectMap = new LinkedHashMap<>();
    private final Multimap<Node, LoopObject> functionLoopObjectsMap =
        LinkedHashMultimap.create();
    private final Multimap<Node, String> functionHandledMap = HashMultimap.create();
    private final Multimap<Var, Node> referenceMap = LinkedHashMultimap.create();

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (!NodeUtil.isReferenceName(n)) {
        return;
      }

      String name = n.getString();
      Scope referencedIn = t.getScope();
      Var var = referencedIn.getVar(name);
      if (var == null) {
        return;
      }

      if (!var.isLet() && !var.isConst()) {
        return;
      }

      if (n.getParent().isLet() || n.getParent().isConst()) {
        letConsts.add(n.getParent());
      }

      // Traverse nodes up from let/const declaration:
      // If we hit a function or the root before a loop - Not a loop closure.
      // if we hit a loop first - maybe loop closure.
      Scope declaredIn = var.getScope();
      Node loopNode = null;
      for (Scope s = declaredIn;; s = s.getParent()) {
        Node scopeRoot = s.getRootNode();
        if (NodeUtil.isLoopStructure(s.getRootNode())) {
          loopNode = scopeRoot;
          break;
        } else if (scopeRoot.getParent() != null
            && NodeUtil.isLoopStructure(scopeRoot.getParent())) {
          loopNode = scopeRoot.getParent();
          break;
        } else if (s.isFunctionBlockScope() || s.isGlobal()) {
          return;
        }
      }

      referenceMap.put(var, n);

      // Traverse scopes from reference scope to declaration scope.
      // If we hit a function - loop closure detected.
      Scope outerMostFunctionScope = null;
      for (Scope s = referencedIn; s != declaredIn && s.getRootNode() != loopNode;
          s = s.getParent()) {
        if (s.isFunctionScope()) {
          outerMostFunctionScope = s;
        }
      }

      if (outerMostFunctionScope != null) {
        Node function = outerMostFunctionScope.getRootNode();
        if (functionHandledMap.containsEntry(function, name)) {
          return;
        }
        functionHandledMap.put(function, name);

        if (!loopObjectMap.containsKey(loopNode)) {
          loopObjectMap.put(loopNode,
              new LoopObject(
                  LOOP_OBJECT_NAME + "$" + compiler.getUniqueNameIdSupplier().get()));
        }
        LoopObject object = loopObjectMap.get(loopNode);
        object.vars.add(var);

        functionLoopObjectsMap.put(function,  object);
      }
    }

    private void transformLoopClosure() {
      if (loopObjectMap.isEmpty()) {
        return;
      }

      for (Node loopNode : loopObjectMap.keySet()) {
        // Introduce objects to reflect the captured scope variables.
        // Fields are initially left as undefined to avoid cases like:
        //   var $jscomp$loop$0 = {i: 0, j: $jscomp$loop$0.i}
        // They are initialized lazily by changing declarations into assignments
        // later.
        LoopObject object = loopObjectMap.get(loopNode);
        Node objectLitNextIteration = IR.objectlit();
        for (Var var : object.vars) {
          objectLitNextIteration.addChildToBack(
              IR.stringKey(var.name, IR.getprop(IR.name(object.name), IR.string(var.name))));
        }

        Node updateLoopObject = IR.assign(IR.name(object.name), objectLitNextIteration);
        Node objectLit =
            IR.var(IR.name(object.name), IR.objectlit()).useSourceInfoFromForTree(loopNode);
        addNodeBeforeLoop(objectLit, loopNode);
        if (loopNode.isVanillaFor()) { // For
          // The initializer is pulled out and placed prior to the loop.
          Node initializer = loopNode.getFirstChild();
          loopNode.replaceChild(initializer, IR.empty());
          if (!initializer.isEmpty()) {
            if (!NodeUtil.isNameDeclaration(initializer)) {
              initializer = IR.exprResult(initializer).useSourceInfoFrom(initializer);
            }
            addNodeBeforeLoop(initializer, loopNode);
          }

          Node increment = loopNode.getChildAtIndex(2);
          if (increment.isEmpty()) {
            loopNode.replaceChild(
                increment,
                updateLoopObject.useSourceInfoIfMissingFromForTree(loopNode));
          } else {
            Node placeHolder = IR.empty();
            loopNode.replaceChild(increment, placeHolder);
            loopNode.replaceChild(
                placeHolder,
                IR.comma(updateLoopObject, increment)
                    .useSourceInfoIfMissingFromForTree(loopNode));
          }
        } else if (loopNode.isDo()) { // do-while, put at the end of the block
          loopNode.getFirstChild().addChildToBack(IR.exprResult(updateLoopObject)
              .useSourceInfoIfMissingFromForTree(loopNode));
        } else { // For-in, for-of or while, put at the end of the block
          loopNode.getLastChild().addChildToBack(IR.exprResult(updateLoopObject)
              .useSourceInfoIfMissingFromForTree(loopNode));
        }
        compiler.reportChangeToEnclosingScope(loopNode);

        // For captured variables, change declarations to assignments on the
        // corresponding field of the introduced object. Rename all references
        // accordingly.
        for (Var var : object.vars) {
          for (Node reference : referenceMap.get(var)) {
            // For-of and for-in declarations are not altered, since they are
            // used as temporary variables for assignment.
            if (NodeUtil.isEnhancedFor(loopNode)
                && loopNode.getFirstChild() == reference.getParent()) {
              loopNode.getLastChild().addChildToFront(
                  IR.exprResult(IR.assign(
                      IR.getprop(IR.name(object.name), IR.string(var.name)),
                      var.getNameNode().cloneNode()))
                      .useSourceInfoIfMissingFromForTree(reference));
            } else {
              if (NodeUtil.isNameDeclaration(reference.getParent())) {
                Node declaration = reference.getParent();
                Node grandParent = declaration.getParent();
                handleDeclarationList(declaration, grandParent);
                declaration = reference.getParent(); // Might have changed after normalization.
                // Change declaration to assignment, or just drop it if there's
                // no initial value.
                if (reference.hasChildren()) {
                  Node newReference = reference.cloneNode();
                  Node assign = IR.assign(newReference, reference.removeFirstChild());
                  extractInlineJSDoc(declaration, reference, declaration);
                  maybeAddConstJSDoc(declaration, grandParent, reference, declaration);
                  assign.setJSDocInfo(declaration.getJSDocInfo());

                  Node replacement = IR.exprResult(assign)
                      .useSourceInfoIfMissingFromForTree(declaration);
                  grandParent.replaceChild(declaration, replacement);
                  reference = newReference;
                } else {
                  grandParent.removeChild(declaration);
                }
                letConsts.remove(declaration);
                compiler.reportChangeToEnclosingScope(grandParent);
              }

              if (reference.getParent().isCall()
                  && reference.getParent().getFirstChild() == reference) {
                reference.getParent().putBooleanProp(Node.FREE_CALL, false);
              }
              // Change reference to GETPROP.
              Node changeScope = NodeUtil.getEnclosingChangeScopeRoot(reference);
              reference.replaceWith(
                  IR.getprop(IR.name(object.name), IR.string(var.name))
                      .useSourceInfoIfMissingFromForTree(reference));
              // TODO(johnlenz): Don't work on detached nodes.
              if (changeScope != null) {
                compiler.reportChangeToChangeScope(changeScope);
              }
            }
          }
        }
      }

      // Create wrapper functions and call them.
      for (Node function : functionLoopObjectsMap.keySet()) {
        Node returnNode = IR.returnNode();
        Collection<LoopObject> objects = functionLoopObjectsMap.get(function);
        Node[] objectNames = new Node[objects.size()];
        Node[] objectNamesForCall = new Node[objects.size()];
        int i = 0;
        for (LoopObject object : objects) {
          objectNames[i] = IR.name(object.name);
          objectNamesForCall[i] = IR.name(object.name);
          i++;
        }

        Node iife = IR.function(
            IR.name(""),
            IR.paramList(objectNames),
            IR.block(returnNode));
        compiler.reportChangeToChangeScope(iife);
        Node call = IR.call(iife, objectNamesForCall);
        call.putBooleanProp(Node.FREE_CALL, true);
        Node replacement;
        if (NodeUtil.isFunctionDeclaration(function)) {
          replacement = IR.var(IR.name(function.getFirstChild().getString()), call)
              .useSourceInfoIfMissingFromForTree(function);
        } else {
          replacement = call.useSourceInfoIfMissingFromForTree(function);
        }
        function.replaceWith(replacement);
        returnNode.addChildToFront(function);
        compiler.reportChangeToEnclosingScope(replacement);
      }
    }

    private class LoopObject {
      private final String name;
      private final Set<Var> vars = new LinkedHashSet<>();

      private LoopObject(String name) {
        this.name = name;
      }
    }
  }
}
