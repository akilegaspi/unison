package org.unisonweb

import org.unisonweb.util.{Functor, Traverse, Monoid}

sealed abstract class ABT[F[+_],+R] {
  import ABT._
  def map[R2](g: R => R2)(implicit F: Functor[F]): ABT[F,R2] = this match {
    case Tm_(f) => Tm_(F.map(f)(g))
    case Abs_(name, body) => Abs_(name, g(body))
    case v@Var_(_) => v
  }
}

object ABT {
  case class Name(override val toString: String) extends AnyVal
  object Name {
    implicit def stringToName(s: String): Name = Name(s)
    implicit def symbolToName(s: Symbol): Name = Name(s.name)
    implicit def stringKeyToName[A](t: (String, A)): (Name, A) = Name(t._1) -> t._2
  }

  case class Var_[F[+_]](name: Name) extends ABT[F,Nothing]
  case class Abs_[F[+_],R](name: Name, body: R) extends ABT[F,R]
  case class Tm_[F[+_],R](f: F[R]) extends ABT[F,R] {
    override def toString = f.toString
  }

  case class AnnotatedTerm[F[+_],A](annotation: A, get: ABT[F,AnnotatedTerm[F,A]]) {
    override def toString = get.toString
    def covary[F2[x]<:F[x]]: AnnotatedTerm[F2,A] = this.asInstanceOf[AnnotatedTerm[F2,A]]
    def map[B](f: A => B)(implicit F: Functor[F]): AnnotatedTerm[F,B] =
      AnnotatedTerm(f(annotation), get.map(_.map(f)))
    def reannotate(f: A => A): AnnotatedTerm[F,A] = AnnotatedTerm(f(annotation), get)

    /** Discard all the annotations and just annotate with the set of free variables. */
    def annotateFree(implicit F: Traverse[F]): AnnotatedTerm[F, Set[Name]] = get match {
      case Var_(name) => Var(name)
      case Abs_(name, body) => Abs(name, body.annotateFree)
      case Tm_(tm) => Tm(F.map(tm)(_ annotateFree))
    }

    /** Push an annotation down from the root. */
    def annotateDown[S, A2](s: S)(f: (S, AnnotatedTerm[F,A]) => (S, A2))(implicit F: Functor[F]): AnnotatedTerm[F, A2] = {
      val (s2, a2) = f(s, this)
      AnnotatedTerm(a2, get.map(_.annotateDown(s2)(f)))
    }

    /** Accumulate `B` values up the tree. `f` is only applied to leaf nodes of the tree (either `Var` or `Tm` with no children). */
    def annotateUp[B](f: AnnotatedTerm[F,A] => AnnotatedTerm[F,B])(B: Monoid[B])(implicit F: Traverse[F]): AnnotatedTerm[F,B] = {
       get.map(_.annotateUp(f)(B)) match {
         case abt @ Tm_(t) =>
           val children = F.toVector(t)
           if (children.isEmpty) f(this)
           else AnnotatedTerm(B.reduce(children.map(_.annotation)), abt)
         case Var_(_) => f(this)
         case abt@Abs_(name, body) => AnnotatedTerm(body.annotation, abt)
       }
    }

    /**
     * Applies `f` to all leaf nodes (either vars or Tm nodes with no children),
     * then accumulates the values up the tree.
     */
    def foldMap[B](f: AnnotatedTerm[F,A] => B)(B: Monoid[B])(
        implicit F: Traverse[F]): B =
      get.map(_.foldMap(f)(B)) match {
         case abt @ Tm_(t) =>
           val children = F.toVector(t)
           if (children.isEmpty) f(this)
           else B.reduce(children)
         case Var_(_) => f(this)
         case Abs_(name, b) => b
      }

    /** Apply `f` to `this`, and then recursively to the children of the resulting term. */
    def rewriteDown(f: AnnotatedTerm[F,A] => AnnotatedTerm[F,A])(
      implicit F: Functor[F]): AnnotatedTerm[F,A] = f(this) match {
        case AnnotatedTerm(ann, abt) => AnnotatedTerm(ann, abt.map(_ rewriteDown f))
      }

    def rewriteUp(f: AnnotatedTerm[F,A] => AnnotatedTerm[F,A])(
      implicit F: Traverse[F]): AnnotatedTerm[F,A] = get match {
         case abt @ Tm_(t) =>
           val children = F.toVector(t)
           if (children.isEmpty) f(this)
           else f(AnnotatedTerm(annotation, Tm_(F.map(t) { _ rewriteUp f })))
         case Var_(_) => f(this)
         case abt@Abs_(name, body) =>
           f(AnnotatedTerm(annotation, ABT.Abs_(name, body.rewriteUp(f))))
       }

    /**
     * Like `rewriteDown`, but the function `f` receives state `S` which is
     * updated while moving down the tree.
     */
    def rewriteDownS[S](s: S)(f: (S,AnnotatedTerm[F,A]) => (S,AnnotatedTerm[F,A]))(
      implicit F: Functor[F]): AnnotatedTerm[F,A] =
      f(s, this) match { case (s, AnnotatedTerm(ann,abt)) =>
        AnnotatedTerm(ann, abt.map(_.rewriteDownS(s)(f)))
      }
  }

  type Term[F[+_]] = AnnotatedTerm[F,Set[Name]]

  /**
   * Annotate an ABT with the ordered vector of bound variables available at each subtree.
   * The first element of the `Vector[Name]` is the innermost bound variable.
   */
  def annotateBound[F[+_],A](self: AnnotatedTerm[F,A])(implicit F: Functor[F]): AnnotatedTerm[F,(A,Vector[Name])] = {
    def go(self: AnnotatedTerm[F,A], env: Vector[Name]): AnnotatedTerm[F,(A,Vector[Name])] = self match {
      case Var(n) => AnnotatedTerm(self.annotation -> env, Var_(n))
      case Tm(f) => AnnotatedTerm(self.annotation -> env, Tm_(F.map(f)(go(_,env))))
      case Abs(name, body) => AnnotatedTerm(self.annotation -> env, Abs_(name, go(body, name +: env)))
    }
    val result = go(self, Vector())
    result
  }

  def rename[F[+_]](from: Name, to: Name)(self: Term[F])(implicit F: Traverse[F]): Term[F] =
    if (!self.annotation.contains(from)) self
    else self match {
      case Var(n) => if (n == from) Var(to) else self
      case Abs(name, body) => Abs(name, rename(from,to)(body))
      case Tm(f) => Tm(F.map(f)(e => rename(from,to)(e)))
    }

  def subst[F[+_]](original: Name, sub: Term[F])(self: Term[F])(implicit F: Traverse[F]): Term[F] =
    if (!self.annotation.contains(original)) self
    else self match {
      case Var(n) => if (n == original) sub else self
      case Abs(name, body) =>
        if (sub.annotation.contains(name)) {
          val name2 = freshen(name, sub.annotation)
          Abs(name2, subst(original,sub)(rename(name, name2)(body)))
        }
        else Abs(name, subst(original, sub)(body))
      case Tm(f) => Tm(F.map(f)(e => subst(original,sub)(e)))
    }

  def substs[F[+_]](subs: Map[Name, Term[F]])(self: Term[F])(implicit F: Traverse[F]): Term[F] =
    substs(subs, subs.values.foldLeft(Set.empty[Name])(_ union _.annotation))(self)

  def substs[F[+_]](subs: Map[Name, Term[F]], taken: Set[Name])(self: Term[F])(implicit F: Traverse[F]): Term[F] =
    // if none of the freeVars of this subtree have a mapping in subs, can skip whole subtree
    if (!subs.keys.exists(original => self.annotation.contains(original))) self
    else self match {
      case Var(n) => subs.getOrElse(n, self)
      case Abs(name, body) =>
        if (taken.contains(name)) {
          val name2 = freshen(name, taken)
          Abs(name2, substs(subs, taken)(rename(name,name2)(body)))
        }
        else Abs(name, substs(subs, taken)(body))
      case Tm(f) => Tm(F.map(f)(e => substs(subs, taken)(e)))
    }

  object Var {
    def unapply[F[+_],A](t: AnnotatedTerm[F,A]): Option[Name] = t.get match {
      case Var_(name) => Some(name)
      case _ => None
    }
    def apply[F[+_]](name: Name): Term[F] = AnnotatedTerm(Set(name), Var_(name))
  }

  object Tm {
    def unapply[F[+_],A](t: AnnotatedTerm[F,A]): Option[F[AnnotatedTerm[F,A]]] = t.get match {
      case Tm_(f) => Some(f)
      case _ => None
    }
    def apply[F[+_]](f: F[Term[F]])(implicit F: Traverse[F]): Term[F] = {
      val fvs = F.toVector(f).map(_.annotation).foldLeft(Set.empty[Name])(_ union _)
      AnnotatedTerm(fvs, Tm_(f))
    }
  }

  object Abs {
    def unapply[F[+_],A](t: AnnotatedTerm[F,A]): Option[(Name, AnnotatedTerm[F,A])] = t.get match {
      case Abs_(name, body) => Some((name, body))
      case _ => None
    }
    def apply[F[+_]](name: Name, body: Term[F]): Term[F] = {
      AnnotatedTerm(body.annotation - name, Abs_(name, body))
    }
  }

  // Matches a nested series of Abs.
  // Example: Abs(x, Abs(y, t)) -> (List(x,y), t)
  object AbsChain {
    def apply[F[+_],A](names: Name*)(t: Term[F]): Term[F] =
      names.foldRight(t) { Abs(_, _) }

    def unapply[F[+_],A](t: AnnotatedTerm[F,A]):
      Option[(List[Name], AnnotatedTerm[F,A])] = {
        def go(names: List[Name],
               t: AnnotatedTerm[F,A]): Option[(List[Name],
                                               AnnotatedTerm[F,A])] = t match {
          case Abs(name, body) => go(name :: names, body)
          case _ => if (names.isEmpty) None else Some((names.reverse, t))
        }
        go(List(), t)
      }
    }

  def freshen(v: Name, taken: Set[Name]): Name =
    if (!taken.contains(v)) v
    else Stream.continually(v).zipWithIndex.map {
      case (name,i) => Name(name.toString + i)
    }.dropWhile(taken.contains).head
}
