package psp
package core
package linear

/**
 * Possible PspStream implementation.
 * 
 * Things to note:
 * 
 * 1. Tries to minimize allocations compared to scala.Stream
 * 2. Avoids storing by-name params, preferring thunks internally
 * 3. Doesn't memoize by default, but as an option
 * 4. Prefers fold() over pattern matching due to Memo
 * 5. Intended to be SOE-safe, obviously.
 * 6. Doesn't have any methods that may "run forever" (e.g. filter)
 */

object PspStream {
  def empty[A] = snil.castTo[PspStream[A]]
  def apply[A](as: A*): PspStream[A] = as.foldRight(snil[A])(_ #:: _)
  def unfold[A](a: A)(f: A => A): PspStream[A] = a #:: unfold(f(a))(f)
  def from(n: Int): PspStream[Int] = unfold(n)(_ + 1)

  implicit def pspStreamOps[A](rhs: => PspStream[A]): PspStreamOps[A] =
    new PspStreamOps(rhs _)

}

class PspStreamOps[A](val rhs: () => PspStream[A]) extends AnyVal {
  def #::(lhs: A): PspStream[A] = new #::(lhs, rhs)
  def #:::(lhs: PspStream[A]): PspStream[A] = lhs append rhs()

  def ~::(lhs: A): PspStream[A] = new ~::(lhs, rhs)
}

sealed trait PspStream[A] extends AnyRef with PspLinear[A] { lhs =>
  def isEmpty: Boolean
  def head: A
  def tail: PspStream[A]

  def memoize: PspStream[A] = this match {
    case x #:: xsf => new ~::(x, xsf)
    case st => st
  }

  def fold[B](b: B)(f: (A, () => PspStream[A]) => B): B = this match {
    case x #:: xsf => f(x, xsf)
    case x ~:: xsf => f(x, xsf)
    case _ => b
  }

  private def upcast[A1 >: A] : PspStream[A1] = this.castTo[PspStream[A1]]

  def take(n: Int): PspStream[A] =
    if (n < 1 || isEmpty) snil[A] else head #:: tail.take(n - 1)

  def drop(n: Int): PspStream[A] = {
    def loop(n: Int, st: PspStream[A]): PspStream[A] =
      if (n < 1 || st.isEmpty) st else loop(n - 1, st.tail)
    loop(n, this)
  }

  def zip[B](rhs: PspStream[B]): PspStream[(A, B)] =
    if (lhs.isEmpty || rhs.isEmpty) snil[(A, B)]
    else (lhs.head, rhs.head) #:: (lhs.tail zip rhs.tail)

  def takeWhile(f: A => Boolean): PspStream[A] =
    if (isEmpty || !f(head)) snil[A] else head #:: tail.takeWhile(f)

  def interleave(xs: PspStream[A]): PspStream[A] =
    if (isEmpty) xs else head #:: (xs interleave tail)

  def append(rhs: => PspStream[A]): PspStream[A] =
    if (isEmpty) rhs else head #:: (tail append rhs)

  def appendAll(rhs: PspLinear[PspStream[A]]): PspStream[A] =
    if (rhs.isEmpty) lhs else lhs append rhs.head.appendAll(rhs.tail)

  final def foreach(f: A => Unit): Unit = {
    @tailrec def loop(p: PspStream[A]): Unit =
      if (!p.isEmpty) { f(p.head); loop(p.tail) } else ()
    loop(this)
  }

  final def map[B](f: A => B): PspStream[B] =
    if (isEmpty) snil[B] else f(head) #:: tail.map(f)

  final def flatMap[B](f: A => PspStream[B]): PspStream[B] =
    if (isEmpty) snil[B] else f(head).append(tail.flatMap(f))

  final override def toString =
    if (isEmpty) "PspStream()" else s"PspStream($head, ...)"
}

final case object snil extends PspStream[Nothing] {
  def sizeInfo = precise(0)
  def isEmpty  = true
  def head     = failEmpty("head")
  def tail     = failEmpty("tail")

  def apply[A](): PspStream[A] = this.castTo[PspStream[A]]
  def unapply[A](xs: PspStream[A]): Boolean = this eq xs
}

final case class #::[A](head: A, tailf: () => PspStream[A]) extends PspStream[A] {
  def sizeInfo = precise(1).atLeast
  def isEmpty = false
  def tail = tailf()
}

final case class ~::[A](head: A, tailf: () => PspStream[A]) extends PspStream[A] {
  def sizeInfo = precise(1).atLeast
  def isEmpty = false
  lazy val tail = tailf().memoize
}
