package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

trait Parameter {
  def ADDR_WIDTH = 32
  def DATA_WIDTH = 32
}

class DiffCsr extends Bundle with Parameter{
  val mcause  = Output(UInt(DATA_WIDTH.W))
  val mepc    = Output(UInt(DATA_WIDTH.W))
  val mstatus = Output(UInt(DATA_WIDTH.W))
  val mtvec   = Output(UInt(DATA_WIDTH.W))
}

class PipelineDebugInfo extends Bundle with Parameter {
    val inst    =   Output(UInt(ADDR_WIDTH.W))
    val pc      =   Output(UInt(ADDR_WIDTH.W))      
}

class out_class extends Bundle with  Parameter {
    val nextExecPC  = Output(UInt(ADDR_WIDTH.W)) // the next execute pc after a wb signal, for difftest(actually the next wb pc?)

    val ifu_fetchPc = Output(UInt(ADDR_WIDTH.W))
    val ifu      = new PipelineDebugInfo
    val idu      = new PipelineDebugInfo
    val isu      = new PipelineDebugInfo
    val exu      = new PipelineDebugInfo
    val wbu      = new PipelineDebugInfo

    val difftest = new DiffCsr
    val is_mmio  = Output(Bool())
    val wb       = Output(Bool())
    val GTimer   = Output(UInt(32.W))
}

object CPUAXI4BundleParameters {
  def apply() = AXI4BundleParameters(addrBits = 32, dataBits = 64, idBits = ChipLinkParam.idBits)
}

class ysyx_00000000 extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val io_interrupt = Input(Bool())
    val io_master = AXI4Bundle(CPUAXI4BundleParameters())
    // val io_slave = Flipped(AXI4Bundle(CPUAXI4BundleParameters()))
    val io_out = new out_class
  })
}

class CPU(idBits: Int)(implicit p: Parameters) extends LazyModule {
  val masterNode = AXI4MasterNode(p(ExtIn).map(params =>
    AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = "cpu",
        id   = IdRange(0, 1 << idBits))))).toSeq)
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (master, _) = masterNode.out(0)
    val interrupt = IO(Input(Bool()))
    // val slave = IO(Flipped(AXI4Bundle(CPUAXI4BundleParameters())))

    val cpu = Module(new ysyx_00000000)
    cpu.io.clock := clock
    cpu.io.reset := reset
    cpu.io.io_interrupt := interrupt
    // cpu.io.io_slave <> slave
    master <> cpu.io.io_master

    val out = IO(new out_class)
    cpu.io.io_out <> out
  }
}
