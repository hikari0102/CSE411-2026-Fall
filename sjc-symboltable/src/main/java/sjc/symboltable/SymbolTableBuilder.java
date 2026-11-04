package sjc.symboltable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.Modifier.ModifierKeyword;

import sjc.annotation.NonNull;
import sjc.annotation.NonNullElements;
import sjc.annotation.ReadOnly;
import sjc.util.Pair;

/**
 * This class is used to build symbol table for an ExtendedStaticJava
 * {@link CompilationUnit}. Note that the algorithm assumes that the JDT AST
 * tree was built using the {@link esjc.ast.ExtendedStaticJavaASTBuilder}. That is, it
 * assumes certain structures on the AST, e.g., a class does not have an
 * instance method.
 *
 */
public class SymbolTableBuilder {
  /** Signals an error while building an extended symbol table. */
  public static class Error extends RuntimeException {
    private static final long serialVersionUID = 7425398360365890486L;

    public final @NonNull
    @ReadOnly
    ASTNode node;

    public Error(@NonNull final ASTNode node, @NonNull final String msg) {
      super(msg);
      assert (msg != null) && (node != null);
      this.node = node;
    }
  }

  /**
   * The visitor for {@link ASTNode} to resolve symbols.
   *
   */
  private static class Visitor extends ASTVisitor {
    public @NonNull
    Map<ASTNode, Object> result = new HashMap<>();

    protected @NonNull
    Map<String, ASTNode> nameMap = new HashMap<>();

    protected @NonNull
    Map<String, MethodDeclaration> methodMap = new HashMap<>();

    protected @NonNull
    Set<String> localNames = new HashSet<>();

    protected String className;

    private @NonNullElements
    Map<String, TypeDeclaration> classMap = new HashMap<>();

    private @NonNullElements
    Map<Pair<String, String>, FieldDeclaration> fieldMap = new HashMap<>();

    /**
     * Unlink references and clear maps/collections.
     */
    protected void dispose() {
      this.result = null;
      if (this.nameMap != null) {
        this.nameMap.clear();
        this.nameMap = null;
      }
      if (this.methodMap != null) {
        this.methodMap.clear();
        this.methodMap = null;
      }
      this.className = null;
    }

    /**
     * Determines whether a {@link List} of {@link Modifier}s has a public
     * modifier.
     * 
     * @param modifiers
     *        the list of modifiers
     *
     * @return True, if the {@link List} contains a public modifier.
     */
    private boolean hasPublicModifier(final List modifiers) {
      for (final Object o : modifiers) {
        if ((o instanceof Modifier)
            && (((Modifier) o).getKeyword() == ModifierKeyword.PUBLIC_KEYWORD)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean visit(final FieldDeclaration node) {
      final VariableDeclarationFragment vdf = (VariableDeclarationFragment) node.fragments().get(0);
      final String name = vdf.getName().getIdentifier();
      if (hasPublicModifier(node.modifiers())) {
        final Pair<String, String> key = new Pair<>(this.className, name);
        if (this.fieldMap.containsKey(key)) {
          throw new Error(node, "Field " + name + " is already declared in class "
              + this.className);
        }
        this.fieldMap.put(key, node);
      } else {
        if (this.nameMap.containsKey(name)) {
          throw new Error(node, "Field " + name + " is already declared");
        }
        this.nameMap.put(name, node);
      }
      return false;
    }

    @Override
    public boolean visit(final MethodDeclaration node) {
      final Map<String, ASTNode> savedNames = new HashMap<>(this.nameMap);
      this.localNames.clear();
      for (final Object o : node.parameters()) {
        final SingleVariableDeclaration svd = (SingleVariableDeclaration)o;
        declareLocal(svd.getName().getIdentifier(), svd);
      }
      node.getBody().accept(this);
      this.nameMap = savedNames;
      return false;
    }

    @Override
    public boolean visit(final MethodInvocation node) {
      final Expression e = node.getExpression();
      if((e == null) || this.className.equals(e.toString())) {
        final String name = node.getName().getIdentifier();
        final MethodDeclaration md = this.methodMap.get(name);
        if (md == null) {
          throw new Error(node, "Method " + name + " is not declared");
        }
        this.result.put(node, md);
      }
      for(final Object o : node.arguments()) {
        ((ASTNode) o).accept(this);
      }
      return false;
    }

    @Override
    public boolean visit(final SimpleName node) {
      final String name = node.getIdentifier();
      final ASTNode decl = this.nameMap.get(name);
      if (decl == null) {
        throw new Error(node, "Variable " + name + " is not declared");
      }
      this.result.put(node, decl);
      return false;
    }

    @Override
    public boolean visit(final VariableDeclarationStatement node) {
      final VariableDeclarationFragment vdf = (VariableDeclarationFragment) node.fragments().get(0);
      declareLocal(vdf.getName().getIdentifier(), node);
      return false;
    }

    private void declareLocal(final String name, final ASTNode decl) {
      if(!this.localNames.add(name)) {
        throw new Error(decl, "Variable " + name + " is already declared");
      }
      this.nameMap.put(name, decl);
    }

    @Override
    public boolean visit(final ArrayCreation node) {
      // Note that we don't visit the ArrayCreation's type
      // because we want visit(SimpleName) to resolve variable references
      // instead of method names.
      for (final Object o : node.dimensions()) {
        ((ASTNode) o).accept(this);
      }
      if (node.getInitializer() != null) {
        node.getInitializer().accept(this);
      }
      return false;
    }

    @Override
    public boolean visit(final ClassInstanceCreation node) {
      // Note that we don't visit the ClassInstanceCreation's name
      // because we want visit(SimpleName) to resolve variable references
      // instead of method names.
      return false;
    }

    @Override
    public boolean visit(final FieldAccess node) {
      // Note that we don't visit the FieldAccess's name
      // because we want visit(SimpleName) to resolve variable references
      // instead of method names.
      node.getExpression().accept(this);
      return false;
    }

    @Override
    public boolean visit(final TypeDeclaration node) {
      this.className = node.getName().getIdentifier();
      if(!hasPublicModifier(node.modifiers())) {
        if(this.classMap.containsKey(this.className)) {
          throw new Error(node, "Class " + this.className
              + " is already declared");
        }
        this.classMap.put(this.className, node);
      }
      for(final Object o : node.bodyDeclarations()) {
        if(o instanceof FieldDeclaration) {
          ((ASTNode) o).accept(this);
        } 
        else if(o instanceof MethodDeclaration) {
          final MethodDeclaration md = (MethodDeclaration) o;
          final String name = md.getName().getIdentifier();
          if(this.methodMap.containsKey(name)) {
            throw new Error(md, "Method " + name + " is already declared");
          }
          this.methodMap.put(name, md);
        }
      }
      for(final Object o : node.bodyDeclarations()) {
        if(o instanceof MethodDeclaration) {
          ((ASTNode) o).accept(this);
        }
      }
      return false;
    }
  }

  /**
   * Builds a {@link SymbolTable} for the given ExtendedStaticJava
   * {@link CompilationUnit}.
   * 
   * @param cu
   *          The {@link CompilationUnit}.
   * @return The {@link SymbolTable} for the given
   *         {@link CompilationUnit}.
   * @throws Error
   *           If the builder encounter unresolvable symbol.
   */
  public static @NonNull
  SymbolTable build(@NonNull final CompilationUnit cu)
      throws Error {
    assert cu != null;

    final Visitor v = new Visitor();
    cu.accept(v);
    final SymbolTable result = new SymbolTable(v.result,
        v.classMap, v.fieldMap);
    v.dispose();
    return result;
  }

  /**
   * Declared as protected to disallow creation of this object outside from the
   * methods of this class.
   */
  protected SymbolTableBuilder() {
  }
}
