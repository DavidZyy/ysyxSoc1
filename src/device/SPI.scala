package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class SPIIO(val ssWidth: Int = 8) extends Bundle {
  val sck = Output(Bool())
  val ss = Output(UInt(ssWidth.W))
  val mosi = Output(Bool())
  val miso = Input(Bool())
}

class spi_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val spi = new SPIIO
    val spi_irq_out = Output(Bool())
  })
}

class flash extends BlackBox {
  val io = IO(Flipped(new SPIIO(1)))
}

// class APBSPI(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
//   val node = APBSlaveNode(Seq(APBSlavePortParameters(
//     Seq(APBSlaveParameters(
//       address       = address,
//       executable    = true,
//       supportsRead  = true,
//       supportsWrite = true)),
//     beatBytes  = 4)))
// 
//   lazy val module = new Impl
//   class Impl extends LazyModuleImp(this) {
//     val (in, _) = node.in(0)
//     val spi_bundle = IO(new SPIIO)
// 
//     val mspi = Module(new spi_top_apb)
//     mspi.io.clock := clock
//     mspi.io.reset := reset
//     mspi.io.in <> in
//     spi_bundle <> mspi.io.spi
//   }
// }

trait spiParameter {
  val xipFlashAddr = 0x30000000L.U

  val SPI_BASE     = 0x10001000L.U
  val SPI_TX0      = 0x00L.U
  val SPI_TX1      = 0x04L.U
  val SPI_RX0      = 0x00L.U
  val SPI_CTRL     = 0x10L.U
  val SPI_DIVIDER  = 0x14L.U
  val SPI_SS       = 0x18L.U
 
  val selFlash    = 0x01L.U
  // 0x100 means set go busy, 0x2040 means from left to right, ass = 1, chen_len = 64
  val spiCtrl     = (0x100L | 0x2040L).U
}

class APBSPI(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule with spiParameter {
  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = true,
      supportsRead  = true,
      supportsWrite = true)),
    beatBytes  = 4)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val spi_bundle = IO(new SPIIO)

    val mspi = Module(new spi_top_apb)

    val s_idle :: s_xip_ss :: s_xip_tx1 :: s_xip_ctrl :: s_xip_wait :: s_xip_polling :: s_xip_end :: s_xip_end_pready :: Nil = Enum(8)
    val state = RegInit(s_idle)

    val flashWdata = Cat(3.U(8.W), in.paddr(23, 0))
    val accessFlash = (in.paddr >= xipFlashAddr)

    val isGoBitSet = (mspi.io.in.prdata & 0x100.U) === 0x100.U

    switch (state) {
      is (s_idle) {
        state := Mux(accessFlash && in.penable, s_xip_ss, s_idle)
      }
      is (s_xip_ss) {
        // it seems that in apb spi_top is always have pready after penable
        // it have no things like ar.ready in axir, it always ready.
        state := s_xip_tx1
      }
      is (s_xip_tx1) {
        state := s_xip_ctrl
      }
      is (s_xip_ctrl) {
        state := s_xip_wait
      }
      is (s_xip_wait) {
        state := s_xip_polling
      }
      is (s_xip_polling) {
        state := Mux(isGoBitSet, s_xip_polling, s_xip_end)
      }
      is (s_xip_end) {
        state := s_xip_end_pready
      }
      is (s_xip_end_pready) {
        state := s_idle
      }
    }

    // apb bus
    mspi.io.clock := clock
    mspi.io.reset := reset

    // mspi.io.in <> in

    mspi.io.in.paddr := MuxLookup(state, 0.U)(List(
      s_idle     -> in.paddr,
      s_xip_ss   -> SPI_SS,
      s_xip_tx1  -> SPI_TX1,
      s_xip_ctrl -> SPI_CTRL,
      s_xip_wait -> SPI_CTRL,
      s_xip_polling -> SPI_CTRL,
      s_xip_end  -> SPI_RX0,
    ))
    mspi.io.in.psel := in.psel
    mspi.io.in.penable := MuxLookup(state, true.B)(List(
      s_idle   -> Mux(!accessFlash, in.penable, false.B),
      s_xip_end_pready -> false.B,
    ))
    mspi.io.in.pprot  := in.pprot
    mspi.io.in.pwrite := MuxLookup(state, false.B)(List(
      s_idle     -> in.pwrite,
      s_xip_ss   -> true.B,
      s_xip_tx1  -> true.B,
      s_xip_ctrl -> true.B,
      s_xip_wait -> false.B,
      s_xip_polling -> false.B,
      s_xip_end  -> false.B,
      s_xip_end_pready -> false.B,
    ))
    mspi.io.in.pwdata := MuxLookup(state, 0.U)(List(
      s_idle     -> in.pwdata,
      s_xip_ss   -> selFlash,
      s_xip_tx1  -> flashWdata,
      s_xip_ctrl -> spiCtrl,
    ))
    mspi.io.in.pstrb := MuxLookup(state, "b1111".U)(List(
      s_idle   -> in.pstrb,
      // s_xip_ss -> "0b1111".U,
    ))
    in.pready := MuxLookup(state, false.B)(List( // also act like r.valid in axi4
      s_idle   -> Mux(!accessFlash, mspi.io.in.pready, false.B),
      s_xip_ss   -> false.B,
      s_xip_tx1  -> false.B,
      s_xip_ctrl -> false.B,
      s_xip_wait -> false.B,
      s_xip_polling -> false.B,
      s_xip_end  -> false.B,
      s_xip_end_pready  -> true.B,
    ))
    in.prdata  := mspi.io.in.prdata
    in.pslverr := mspi.io.in.pslverr
    
    // spi bus
    spi_bundle <> mspi.io.spi
  }
}

/*
      +-----------------+
      |   +--------+    |
      |   |        |    |
apb---|-> |  mspi  |----|--->spi
      |   |        |    |
      |   +--------+    |
      +-----------------+
*/
