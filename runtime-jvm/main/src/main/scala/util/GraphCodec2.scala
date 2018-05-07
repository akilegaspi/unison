package org.unisonweb.util

import java.util

import org.unisonweb.Param
import org.unisonweb.Term.Term

object Codec {
  
  def encodeTerm(sink: Sink,
                 seenTerm: Term => Option[Long],
                 seenParam: Param => Option[Long]): Term => Unit = {
    def go(t: Term): Unit = seenTerm(t) match {
      case None => /* match on `t` and write some shit out, calling `go` recursively */
      case Some(pos) => /* Write out a reference to `pos`. */
    }
    go(_)
  }

  //def encodeParam(sink: Sink, encodeTerm)

  def encodeTermWithSharing(sink: Sink): Term => Unit = {
    val seenTerms = new util.IdentityHashMap[Term,Long]()
    val seenParams = new util.IdentityHashMap[Param,Long]()
    encodeTerm(
        sink,
        // in the event seen returns None, update the map to contain a t -> sink.position entry
        t => if (seenTerms.containsKey(t)) Some(seenTerms.get(t)) else None,
        p => if (seenParams.containsKey(p)) Some(seenParams.get(p)) else None
    )
  }
  
//  def encodeTerm(sink: Sink): Term => Unit = ???
//  def encodeRef(valueLocation: Long, sink: Sink): Unit = ???
//  def encodeParam(sink: Sink): Param => Unit = ???

}

