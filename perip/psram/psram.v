`define ioWidth    4
`define cmdWidth   8
`define addrWidth  24
`define dataWidth  32
`define counterWidth   8

`define ioCmdWidth 1 // the transfer width of the cmd
`define ioAddrWidth 4
`define ioDataWidth 4

module psram (
  input sck,
  input ce_n,
  inout [`ioWidth-1:0] dio
);

  parameter cmdCounter  = `counterWidth'd`cmdWidth / `counterWidth'd`ioCmdWidth - `counterWidth'd1;
  parameter addrCounter = `counterWidth'd`addrWidth / `counterWidth'd`ioAddrWidth - `counterWidth'd1;
  parameter sramElemCnt = (2 ** `addrWidth) / (`dataWidth / 8);
  parameter deleayCounter = 6

  assign dio = 4'bz;

  wire reset = ce_n;
  typedef enum [2:0] { cmd_t, addr_t, delay_t, data_t, err_t } state_t;
  reg [2:0]  state;
  reg [7:0]  counter;
  reg [`cmdWidth-1:0]  cmd;
  reg [`addrWidth-1:0] addr; // addr has only 24 bits, totally 16MB?
  reg [`dataWidth-1:0] wdata;

  reg [`dataWidth-1:0]  sram[0:sramElemCnt];

  initial begin
      sram[0] = 32'hdeadbeef;
      sram[1] = 32'hdeadbeef;
      sram[1] = 32'hdeadbeef;
  end

  wire ren = (state == addr_t) && (counter == addrCounter);
  wire [`dataWidth-1:0] rdata;

  // state machine
  always@(posedge sck or posedge reset) begin
    if (reset) state <= cmd_t;
    else begin
      case (state)
        cmd_t:  state <=  (counter == cmdCounter ) ? addr_t : state;
        addr_t: state <=  (cmd != 8'heb) ? err_t :
                          (counter == addrCounter) ? delay_t : state;
        delay_t: state <= (counter == deleayCounter-1) ? data_t : state;
        data_t: state <= state;

        default: begin
          state <= state;
          $fwrite(32'h80000002, "Assertion failed: Unsupported command `%xh`, only support `ebh` or '38h' read command\n", cmd);
          $fatal;
        end
        default: 
      endcase
    end
  end

  // counter
  always@(posedge sck or posedge reset) begin
    if (reset) counter <= 8'd0;
    else begin
      case (state)
        cmd_t:   counter <= (counter < cmdCounter ) ? counter + 8'd1 : 8'd0;
        addr_t:  counter <= (counter < addrCounter) ? counter + 8'd1 : 8'd0;
        delay_t: counter <= (counter < deleayCounter-1) ? counter + 8'd1 : 8'd0;
        default: counter <= counter + 8'd1;
      endcase
    end
  end

  // cmd
  always@(posedge sck or posedge reset) begin
    if (reset)               cmd <= 8'd0;
    else if (state == cmd_t) cmd <= { cmd[`cmdWidth-1-`ioCmdWidth:0], dio[`ioCmdWidth-1:0]};
  end

  // addr
  always@(posedge sck or posedge reset) begin
    if (reset) addr <= 24'd0;
    else if (state == addr_t && counter < addrCounter)
      addr <= { addr[`addrWidth-1-`ioAddrWidth:0], dio[`ioAddrWidth-1:0] };
  end

  // transfer data
  always@(posedge sck or posedge reset) begin
    if (reset) wdata <= 32'd0;
    else if (state == data_t) begin
      rdata_shift <= {{counter == `counterWidth'd0 ? rdata : rdata_shift}[`dataWidth-1-`iodataWidth:0], `iodataWidth'b0};
    end
  end

  wire [`addrWidth-2:0] raddr = {addr[`addrWidth-1-`ioaddrWidth:0], dio[`ioWidth-1:2]} // shift right 2 bit to align to four bytes
  assign rdata = sram[addr];

  assign dio = ce_n ? 1'b1 : 
               (state == delay_t) ? 1'bz : 
               ({(state == data_t && counter == 8'd0) ? rdata : rdata_shift}[`dataWidth-1:`dataWidth-`ioWidth]);

endmodule
