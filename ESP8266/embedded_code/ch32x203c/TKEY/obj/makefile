################################################################################
# MRS Version: 2.5.0
# Automatically-generated file. Do not edit!
################################################################################
-include ../makefile.init

RM := rm -rf

# All of the sources participating in the build are defined here
-include sources.mk
-include User/subdir.mk
-include Startup/subdir.mk
-include Peripheral/src/subdir.mk
-include Debug/subdir.mk
-include Core/subdir.mk
-include subdir.mk
-include objects.mk

ifneq ($(MAKECMDGOALS),clean)
ifneq ($(strip $(S_DEPS)),)
-include $(S_DEPS)
endif
ifneq ($(strip $(S_UPPER_DEPS)),)
-include $(S_UPPER_DEPS)
endif
ifneq ($(strip $(ASM_DEPS)),)
-include $(ASM_DEPS)
endif
ifneq ($(strip $(ASM_UPPER_DEPS)),)
-include $(ASM_UPPER_DEPS)
endif
ifneq ($(strip $(C_DEPS)),)
-include $(C_DEPS)
endif
endif

-include ../makefile.defs

# Add inputs and outputs from these tool invocations to the build variables 
SECONDARY_FLASH += \
TOUCHKEY.hex \

SECONDARY_LIST += \
TOUCHKEY.lst \

SECONDARY_SIZE += \
TOUCHKEY.siz \


# All Target
all: 
	$(MAKE) --no-print-directory main-build 

main-build: TOUCHKEY.elf secondary-outputs

# Tool invocations
TOUCHKEY.elf: $(OBJS) $(USER_OBJS_ESCAPE)
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -T "e:\AndroidProject\SmartHomeApp\embedded_code\ch32x203c\SRC\Ld\Link.ld" -nostartfiles -Xlinker --gc-sections -Wl,-Map,"TOUCHKEY.map" --specs=nano.specs --specs=nosys.specs -o "TOUCHKEY.elf" $(OBJS) $(LIBS)
TOUCHKEY.hex: TOUCHKEY.elf
	@	riscv-none-embed-objcopy -O ihex "TOUCHKEY.elf" "TOUCHKEY.hex"
TOUCHKEY.lst: TOUCHKEY.elf
	@	riscv-none-embed-objdump --all-headers --demangle --disassemble -M xw "TOUCHKEY.elf" > "TOUCHKEY.lst"
TOUCHKEY.siz: TOUCHKEY.elf
	riscv-none-embed-size --format=berkeley "TOUCHKEY.elf"

# Other Targets
clean:
	-$(RM) $(DIR_OBJS) $(SECONDARY_FLASH)$(SECONDARY_LIST)$(SECONDARY_SIZE) TOUCHKEY.elf
	-$(RM) $(DIR_EXPANDS) $(CALLGRAPH_DOT)
	-$(RM) $(DIR_DEPS)
secondary-outputs: $(SECONDARY_FLASH) $(SECONDARY_LIST) $(SECONDARY_SIZE)

.PHONY: all clean dependents

-include ../makefile.targets