V_FILE_GEN   = build/ysyxSoCTop.sv
V_FILE_FINAL = build/ysyxSoCFull.v
SCALA_FILES = $(shell find src/ -name "*.scala")

# --throw-on-first-error: this flag report error
$(V_FILE_FINAL): $(SCALA_FILES)
#./mill.sh -i ysyxsoc.runMain ysyx.Elaborate --target-dir $(@D)
	./mill.sh -i ysyxsoc.runMain ysyx.Elaborate --throw-on-first-error --target-dir $(@D)
	mv $(V_FILE_GEN) $@
# sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g' $@
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $@

	sed -i '1i /* verilator lint_off DECLFILENAME */\n/* verilator lint_off UNUSEDSIGNAL */\n/* verilator lint_off UNDRIVEN */\n/* verilator lint_off UNOPTFLAT */\n/* verilator lint_off WIDTHEXPAND */\n/* verilator lint_off PINCONNECTEMPTY */\n/* verilator lint_off WIDTHTRUNC */' $@
	sed -i 's/module ysyxSoCTop(/module top(/g' $@
	cp $@ /home/zhuyangyang/project/ysyx-workbench/npc/vsrc

verilog: $(V_FILE_FINAL)

clean:
	-rm -rf build/

dev-init:
	git submodule update --init --recursive
	cd rocket-chip && git apply ../patch/rocket-chip.patch

.PHONY: verilog clean dev-init
