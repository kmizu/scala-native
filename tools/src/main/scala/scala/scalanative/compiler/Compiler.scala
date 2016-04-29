package scala.scalanative
package compiler

import scala.collection.mutable
import linker.Linker
import nir._, Shows._
import nir.serialization._
import util.sh

final class Compiler(opts: Opts) {
  private lazy val entry =
    Global.Member(Global.Val(opts.entry), "main_class.ssnr.RefArray_void")

  private lazy val assembly: Seq[Defn] = {
    val (unresolved, assembly) = (new Linker(opts.classpath)).link(entry)

    if (unresolved.nonEmpty) {
      println(s"unresolved deps:")
      unresolved.map(u => sh"  $u".toString).sorted.foreach(println(_))
    }

    assembly
  }

  private lazy val passes: Seq[Pass] = {
    implicit val fresh     = Fresh("tx")
    implicit val hierarchy = analysis.ClassHierarchy(assembly)

    Seq(
        new pass.MainInjection(entry),
        new pass.ClosureLowering,
        new pass.ExternalHoisting,
        new pass.ModuleLowering,
        new pass.TraitLowering,
        new pass.ClassLowering,
        new pass.StringLowering,
        new pass.SizeLowering,
        new pass.ExceptionLowering,
        new pass.CopyPropagation
    )
  }

  private def output(assembly: Seq[Defn]): Unit =
    serializeFile(codegen.GenTextualLLVM, assembly, opts.outpath)

  private def debug(assembly: Seq[Defn], suffix: String) =
    if (opts.verbose)
      serializeFile(
          codegen.GenTextualNIR, assembly, opts.outpath + s".$suffix.hnir")

  def apply(): Unit = {
    def loop(assembly: Seq[Defn], passes: Seq[(Pass, Int)]): Seq[Defn] =
      passes match {
        case Seq() =>
          assembly
        case (pass, id) +: rest =>
          val nassembly = pass(assembly)
          debug(
              nassembly, (id + 1).toString + "-" + pass.getClass.getSimpleName)
          loop(nassembly, rest)
      }
    debug(assembly, "0")
    output(loop(assembly, passes.zipWithIndex))
  }
}
