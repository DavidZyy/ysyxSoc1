package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.system.SimAXIMem

object AXI4SlaveNodeGenerator {
  def apply(params: Option[MasterPortParams], address: Seq[AddressSet])(implicit valName: ValName) =
    AXI4SlaveNode(params.map(p => AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = address,
          executable    = p.executable,
          supportsWrite = TransferSizes(1, p.maxXferBytes),
          supportsRead  = TransferSizes(1, p.maxXferBytes))),
        beatBytes = p.beatBytes
      )).toSeq)
}

class ysyxSoCASIC(implicit p: Parameters) extends LazyModule {
  val xbar = AXI4Xbar()
  // val xbar2 = AXI4Xbar()
  val apbxbar = LazyModule(new APBFanout).node
  val cpu = LazyModule(new CPU(idBits = ChipLinkParam.idBits))
  val chipMaster = if (Config.hasChipLink) Some(LazyModule(new ChipLinkMaster)) else None
  val chiplinkNode = if (Config.hasChipLink) Some(AXI4SlaveNodeGenerator(p(ExtBus), ChipLinkParam.allSpace)) else None

  val luart = LazyModule(new APBUart16550(AddressSet.misaligned(0x10000000, 0x1000)))
  // val lgpio = LazyModule(new APBGPIO(AddressSet.misaligned(0x10002000, 0x10)))
  // val lkeyboard = LazyModule(new APBKeyboard(AddressSet.misaligned(0x10011000, 0x8)))
  // val lvga = LazyModule(new APBVGA(AddressSet.misaligned(0x21000000, 0x200000)))
  val lspi  = LazyModule(new APBSPI(
    AddressSet.misaligned(0x10001000, 0x1000) ++    // SPI controller
    AddressSet.misaligned(0x30000000, 0x10000000)   // XIP flash
  ))
  val lpsram = LazyModule(new APBPSRAM(AddressSet.misaligned(0x80000000L, 0x400000))) // 4MB
  val lmrom = LazyModule(new AXI4MROM(AddressSet.misaligned(0x20000000, 0x1000)))
  val sramNode = AXI4RAM(AddressSet.misaligned(0x0f000000, 0x2000).head, false, true, 8, None, Nil, false)

  val sdramAddressSet = AddressSet.misaligned(0xa0000000L, 0x2000000*4) //0x2000000 is 32MB, one sdram is 32MB, bit and byte extension to 128MB
  val lsdram_apb = if (!Config.sdramUseAXI) Some(LazyModule(new APBSDRAM (sdramAddressSet))) else None
  val lsdram_axi = if ( Config.sdramUseAXI) Some(LazyModule(new AXI4SDRAM(sdramAddressSet))) else None

  // List(lspi.node, luart.node, lpsram.node, lgpio.node, lkeyboard.node, lvga.node).map(_ := apbxbar)
  List(lspi.node, luart.node, lpsram.node).map(_ := apbxbar)
  // List(lspi.node, luart.node).map(_ := apbxbar)
  List(apbxbar := APBDelayer() := AXI4ToAPB(), lmrom.node, sramNode).map(_ := xbar)
  // xbar2 := AXI4UserYanker(Some(1)) := AXI4Fragmenter() := xbar
  if (Config.sdramUseAXI) lsdram_axi.get.node := ysyx.AXI4Delayer() := xbar
  else                    lsdram_apb.get.node := apbxbar
  if (Config.hasChipLink) chiplinkNode.get := xbar
  xbar := cpu.masterNode
 
  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with DontTouch {
    // generate delayed reset for cpu, since chiplink should finish reset
    // to initialize some async modules before accept any requests from cpu
    cpu.module.reset := SynchronizerShiftReg(reset.asBool, 10) || reset.asBool

    val fpga_io = if (Config.hasChipLink) Some(IO(chiselTypeOf(chipMaster.get.module.fpga_io))) else None
    if (Config.hasChipLink) {
      // connect chiplink slave interface to crossbar
      (chipMaster.get.slave zip chiplinkNode.get.in) foreach { case (io, (bundle, _)) => io <> bundle }

      // connect chiplink dma interface to cpu
      // cpu.module.slave <> chipMaster.get.master_mem(0)

      // expose chiplink fpga I/O interface as ports
      fpga_io.get <> chipMaster.get.module.fpga_io
    } else {
      // cpu.module.slave := DontCare
    }

    // connect interrupt signal to cpu
    val intr_from_chipSlave = IO(Input(Bool()))
    cpu.module.interrupt := intr_from_chipSlave

    val sdramBundle = if (Config.sdramUseAXI) lsdram_axi.get.module.sdram_bundle
                      else                    lsdram_apb.get.module.sdram_bundle

    // expose slave I/O interface as ports
    val spi = IO(chiselTypeOf(lspi.module.spi_bundle))
    val uart = IO(chiselTypeOf(luart.module.uart))
    val psram = IO(chiselTypeOf(lpsram.module.qspi_bundle))
    val sdram = IO(chiselTypeOf(sdramBundle))
    // val gpio = IO(chiselTypeOf(lgpio.module.gpio_bundle))
    // val ps2 = IO(chiselTypeOf(lkeyboard.module.ps2_bundle))
    // val vga = IO(chiselTypeOf(lvga.module.vga_bundle))
    uart <> luart.module.uart
    spi <> lspi.module.spi_bundle
    psram <> lpsram.module.qspi_bundle
    sdram <> sdramBundle
    // gpio <> lgpio.module.gpio_bundle
    // ps2 <> lkeyboard.module.ps2_bundle
    // vga <> lvga.module.vga_bundle

    val out = IO(new out_class)
    out <> cpu.module.out
  }
}

class ysyxSoCFPGA(implicit p: Parameters) extends ChipLinkSlave


class ysyxSoCFull(implicit p: Parameters) extends LazyModule {
  val asic = LazyModule(new ysyxSoCASIC)
  ElaborationArtefacts.add("graphml", graphML)

  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with DontTouch {
    val masic = asic.module

    if (Config.hasChipLink) {
      val fpga = LazyModule(new ysyxSoCFPGA)
      val mfpga = Module(fpga.module)
      masic.dontTouchPorts()

      masic.fpga_io.get.b2c <> mfpga.fpga_io.c2b
      mfpga.fpga_io.b2c <> masic.fpga_io.get.c2b

      (fpga.master_mem zip fpga.axi4MasterMemNode.in).map { case (io, (_, edge)) =>
        val mem = LazyModule(new SimAXIMem(edge,
          base = ChipLinkParam.mem.base, size = ChipLinkParam.mem.mask + 1))
        Module(mem.module)
        mem.io_axi4.head <> io
      }

      fpga.master_mmio.map(_ := DontCare)
      fpga.slave.map(_ := DontCare)
    }

    masic.intr_from_chipSlave := false.B

    val flash = Module(new flash)
    flash.io <> masic.spi
    flash.io.ss := masic.spi.ss(0)
    val bitrev = Module(new bitrev)
    bitrev.io <> masic.spi
    bitrev.io.ss := masic.spi.ss(7)
    // masic.spi.miso := List(bitrev.io, flash.io).map(_.miso).reduce(_&&_)
    masic.spi.miso := List(bitrev.io, flash.io).map(_.miso).reduce(_||_)
    // masic.spi.miso := List(bitrev.io).map(_.miso).reduce(_&&_)

    val psram = Module(new psram)
    psram.io <> masic.psram

    val sdram0 = Module(new sdram)
    val sdram1 = Module(new sdram)
    val sdram2 = Module(new sdram)
    val sdram3 = Module(new sdram)
    // val m2s = Module(new sdram_m2s)
    // m2s.io.in <> masic.sdram
    // sdram0.io <> m2s.io.out0
    // sdram1.io <> m2s.io.out1
    sdram0.io.clk := masic.sdram.clk
    sdram0.io.cke := masic.sdram.cke
    sdram0.io.cs  := masic.sdram.cs0
    sdram0.io.ras := masic.sdram.ras
    sdram0.io.cas := masic.sdram.cas
    sdram0.io.we  := masic.sdram.we
    sdram0.io.a   := masic.sdram.a
    sdram0.io.ba  := masic.sdram.ba
    sdram0.io.dqm := masic.sdram.dqm(1, 0)
    sdram0.io.dq  <> masic.sdram.dq0

    sdram1.io.clk := masic.sdram.clk
    sdram1.io.cke := masic.sdram.cke
    sdram1.io.cs  := masic.sdram.cs0
    sdram1.io.ras := masic.sdram.ras
    sdram1.io.cas := masic.sdram.cas
    sdram1.io.we  := masic.sdram.we
    sdram1.io.a   := masic.sdram.a
    sdram1.io.ba  := masic.sdram.ba
    sdram1.io.dqm := masic.sdram.dqm(3, 2)
    sdram1.io.dq  <> masic.sdram.dq1

    sdram2.io.clk := masic.sdram.clk
    sdram2.io.cke := masic.sdram.cke
    sdram2.io.cs  := masic.sdram.cs1
    sdram2.io.ras := masic.sdram.ras
    sdram2.io.cas := masic.sdram.cas
    sdram2.io.we  := masic.sdram.we
    sdram2.io.a   := masic.sdram.a
    sdram2.io.ba  := masic.sdram.ba
    // sdram2.io.dqm := masic.sdram.dqm(3, 2) // bugs here!
    sdram2.io.dqm := masic.sdram.dqm(1, 0)
    sdram2.io.dq  <> masic.sdram.dq2

    sdram3.io.clk := masic.sdram.clk
    sdram3.io.cke := masic.sdram.cke
    sdram3.io.cs  := masic.sdram.cs1
    sdram3.io.ras := masic.sdram.ras
    sdram3.io.cas := masic.sdram.cas
    sdram3.io.we  := masic.sdram.we
    sdram3.io.a   := masic.sdram.a
    sdram3.io.ba  := masic.sdram.ba
    sdram3.io.dqm := masic.sdram.dqm(3, 2)
    sdram3.io.dq  <> masic.sdram.dq3

    val externalPins = IO(new Bundle{
      // val gpio = chiselTypeOf(masic.gpio)
      // val ps2 = chiselTypeOf(masic.ps2)
      // val vga = chiselTypeOf(masic.vga)
      val uart = chiselTypeOf(masic.uart)
    })
    // externalPins.gpio <> masic.gpio
    // externalPins.ps2 <> masic.ps2
    // externalPins.vga <> masic.vga
    externalPins.uart <> masic.uart

    val out = IO(new out_class)
    out <> masic.out
  }
}
