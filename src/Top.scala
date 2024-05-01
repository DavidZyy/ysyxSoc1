package ysyx

import chisel3._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.system.DefaultConfig
import freechips.rocketchip.diplomacy.LazyModule

object Config {
  def hasChipLink: Boolean = false
  def sdramUseAXI: Boolean = false
}

class ysyxSoCTop extends Module {
  implicit val config: Parameters = new DefaultConfig

  // val io = IO(new Bundle { })
  val io = IO(new Bundle { 
    val out = (new out_class)
  })
  val dut = LazyModule(new ysyxSoCFull)
  val mdut = Module(dut.module)
  mdut.dontTouchPorts()
  mdut.externalPins := DontCare

  io.out <> mdut.out
}

object Elaborate extends App {
  // val firtoolOptions = Array("--disable-annotation-unknown")
  val firtoolOptions = Array(
        "--disable-annotation-unknown",
        "--disable-all-randomization",
        "--lowering-options=disallowLocalVariables, locationInfoStyle=none",
        // FirtoolOption("--lowering-options=locationInfoStyle=none")
  )
  circt.stage.ChiselStage.emitSystemVerilogFile(new ysyxSoCTop, args, firtoolOptions)
}
