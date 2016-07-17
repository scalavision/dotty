package dotty.tools.dotc
package transform.linker
import core._
import Contexts.Context
import Flags._
import Symbols._
import SymDenotations._
import Types._
import Decorators._
import DenotTransformers._
import StdNames._
import NameOps._
import ast.Trees._
import dotty.tools.dotc.ast.tpd
import util.Positions._
import java.util

import Names._
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.transform.{Erasure, Splitter, TreeTransforms}
import dotty.tools.dotc.transform.TreeTransforms.{MiniPhaseTransform, TransformerInfo, TreeTransform}

import scala.collection.JavaConversions
import collection.mutable
import scala.collection.immutable.::

/** This phase applies rewritings provided by libraries. */
class IdempotencyInference extends MiniPhaseTransform with IdentityDenotTransformer {
  thisTransform =>
  def phaseName: String = "IdempotencyInference"
  import tpd._

  /** List of names of phases that should precede this phase */
  override def runsAfter: Set[Class[_ <: Phase]] = Set(classOf[Splitter])

  private val collectedCalls = mutable.Map[Symbol, mutable.Set[Symbol]]()
  private val inferredIdempotent = mutable.Set[Symbol]()

  // TODO: check overriding rules.

  override def transformDefDef(tree: tpd.DefDef)(implicit ctx: Context, info: TransformerInfo):tpd.Tree = {
    val calls = collection.mutable.Set[Symbol]()
    tree.rhs.foreachSubTree {
      case t: RefTree =>
        if (!t.symbol.isContainedIn(tree.symbol))
          calls += t.symbol
      case _ =>
    }
    if (tree.rhs.isEmpty || tree.symbol.isSetter) calls += defn.throwMethod
    collectedCalls.put(tree.symbol, calls)
    tree
  }


  override def transformUnit(tree: tpd.Tree)(implicit ctx: Context, info: TransformerInfo): tpd.Tree = {
    var changed = true
    while (changed) {
      changed = false
      collectedCalls.foreach { case (defn, calls) =>
          if (!inferredIdempotent(defn)) {
            if (calls.forall(isIdempotentRef)) {
              if ((!defn.symbol.isConstructor) || (defn.symbol.owner.isValueClass || defn.symbol.owner.is(Flags.Module))) {
                changed = true
                inferredIdempotent += defn
                println(s"Inferred ${defn.showFullName} idempotent")
              }
            }
          }
      }
      println(s" * * * * Marked as idempotent ${inferredIdempotent.size} out of ${collectedCalls.size} methods")
    }

    tree
  }

  /** Expressions known to be initialized once are idempotent (lazy vals
   * and vals), as well as methods annotated with `Idempotent` */
  def isIdempotentRef(sym: Symbol)(implicit ctx: Context): Boolean = {
    if ((sym hasAnnotation defn.IdempotentAnnot) || inferredIdempotent(sym)) true // @Idempotent
    else if (sym is Lazy) true // lazy val and singleton objects
    else if (!(sym is Mutable) && !(sym is Method)) true // val
    else if (sym.maybeOwner.isPrimitiveValueClass) true
    else if (sym == defn.Object_ne || sym == defn.Object_eq) true
    else if (sym == defn.Any_getClass || sym == defn.Any_asInstanceOf || sym == defn.Any_isInstanceOf) true
    else if (Erasure.Boxing.isBox(sym) ||  Erasure.Boxing.isUnbox(sym)) true
    else if (sym.isPrimaryConstructor && sym.owner.is(Flags.Module)) true
    else sym.isGetter && !(sym is Mutable)
  }

  def isIdempotent(tree: Tree)(implicit ctx: Context): Boolean = {
    def loop(tree: Tree, isTopLevel: Boolean = false): Boolean = {
      tree match {
        case This(_) | Super(_, _) => true
        case EmptyTree | Literal(_) if !isTopLevel=> true
        case Ident(_) if !isTopLevel => isIdempotentRef(tree.symbol)
        case Select(qual, _) => loop(qual) && isIdempotentRef(tree.symbol)
        case TypeApply(fn, _) => loop(fn)
        case Apply(fn, args) => loop(fn) && (args forall (t => loop(t)))
        case Typed(expr, _) => loop(expr)
        case _ => false
      }
    }
    val res = loop(tree, isTopLevel = true)
    res
  }
}
