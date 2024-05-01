// shift_reg acts like a stack, shift_reg[0] is the stack top, 
// data in stack top first, and out stack top fist.
module bitrev (
  input  sck,
  input  ss,
  input  mosi,
  output miso
);

  // Initialize state machine
  initial begin
    state_bitrev = s_idle;
    counter = 3'd0;
    shift_reg = 8'd0;
  end

  // State machine to reverse the bits
  reg[1:0] state_bitrev;
  parameter s_idle = 2'b00;
  parameter s_receive = 2'b01;
  parameter s_transmit = 2'b10;

  reg [7:0] shift_reg; // Shift register to store incoming data
  reg [2:0] counter;   // Counter to keep track of bits

  always @ (posedge sck) begin
    case (state_bitrev)
      // counter should be 0.
      s_idle: begin
        if (ss == 1'b1) begin
          state_bitrev <= s_receive;
          counter <= counter + 1;
          shift_reg <= {shift_reg[6:0], mosi}; // shift left
        end
      end
      s_receive: begin
        if (counter == 3'd7) begin
          state_bitrev <= s_transmit;
        end
        counter <= counter + 1;
        shift_reg <= {shift_reg[6:0], mosi};
      end
      s_transmit: begin
        // counter should be 0 at the fisrt in s_transmit.
        if (counter == 3'd7) begin
          state <= s_idle;
        end
        counter <= counter + 1;
        shift_reg <= {1'b0, shift_reg[7:1]};
      end
    endcase
  end

  assign miso = shift_reg[0];

endmodule
