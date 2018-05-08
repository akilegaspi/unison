package org.unisonweb

import org.unisonweb.EasyTest._
//import org.unisonweb.util.PrettyPrint
import Term.Syntax._
import Term.Term
import BuiltinTypes._

object CodecsTests {
  val env0 = Environment.standard

  def roundTrip(t: Term) = {
    Codecs.decodeTerm(Codecs.encodeTerm(t))
  }

  val tests = suite("codecs") (
    test("huge tuple") { implicit T =>
      roundTrip(Tuple.term(replicate(100000)(intIn(0,100):Term):_*))
      ok
    }
  )
}


