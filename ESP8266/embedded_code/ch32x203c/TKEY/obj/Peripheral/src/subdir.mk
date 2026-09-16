################################################################################
# MRS Version: 2.5.0
# Automatically-generated file. Do not edit!
################################################################################

# Add inputs and outputs from these tool invocations to the build variables 
C_SRCS += \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_adc.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_bkp.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_can.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_crc.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_dbgmcu.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_dma.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_exti.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_flash.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_gpio.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_i2c.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_iwdg.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_misc.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_opa.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_pwr.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_rcc.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_rtc.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_spi.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_tim.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_usart.c \
e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_wwdg.c 

C_DEPS += \
./Peripheral/src/ch32v20x_adc.d \
./Peripheral/src/ch32v20x_bkp.d \
./Peripheral/src/ch32v20x_can.d \
./Peripheral/src/ch32v20x_crc.d \
./Peripheral/src/ch32v20x_dbgmcu.d \
./Peripheral/src/ch32v20x_dma.d \
./Peripheral/src/ch32v20x_exti.d \
./Peripheral/src/ch32v20x_flash.d \
./Peripheral/src/ch32v20x_gpio.d \
./Peripheral/src/ch32v20x_i2c.d \
./Peripheral/src/ch32v20x_iwdg.d \
./Peripheral/src/ch32v20x_misc.d \
./Peripheral/src/ch32v20x_opa.d \
./Peripheral/src/ch32v20x_pwr.d \
./Peripheral/src/ch32v20x_rcc.d \
./Peripheral/src/ch32v20x_rtc.d \
./Peripheral/src/ch32v20x_spi.d \
./Peripheral/src/ch32v20x_tim.d \
./Peripheral/src/ch32v20x_usart.d \
./Peripheral/src/ch32v20x_wwdg.d 

OBJS += \
./Peripheral/src/ch32v20x_adc.o \
./Peripheral/src/ch32v20x_bkp.o \
./Peripheral/src/ch32v20x_can.o \
./Peripheral/src/ch32v20x_crc.o \
./Peripheral/src/ch32v20x_dbgmcu.o \
./Peripheral/src/ch32v20x_dma.o \
./Peripheral/src/ch32v20x_exti.o \
./Peripheral/src/ch32v20x_flash.o \
./Peripheral/src/ch32v20x_gpio.o \
./Peripheral/src/ch32v20x_i2c.o \
./Peripheral/src/ch32v20x_iwdg.o \
./Peripheral/src/ch32v20x_misc.o \
./Peripheral/src/ch32v20x_opa.o \
./Peripheral/src/ch32v20x_pwr.o \
./Peripheral/src/ch32v20x_rcc.o \
./Peripheral/src/ch32v20x_rtc.o \
./Peripheral/src/ch32v20x_spi.o \
./Peripheral/src/ch32v20x_tim.o \
./Peripheral/src/ch32v20x_usart.o \
./Peripheral/src/ch32v20x_wwdg.o 

DIR_OBJS += \
./Peripheral/src/*.o \

DIR_DEPS += \
./Peripheral/src/*.d \

DIR_EXPANDS += \
./Peripheral/src/*.234r.expand \


# Each subdirectory must supply rules for building sources it contributes
Peripheral/src/ch32v20x_adc.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_adc.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_bkp.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_bkp.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_can.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_can.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_crc.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_crc.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_dbgmcu.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_dbgmcu.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_dma.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_dma.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_exti.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_exti.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_flash.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_flash.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_gpio.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_gpio.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_i2c.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_i2c.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_iwdg.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_iwdg.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_misc.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_misc.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_opa.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_opa.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_pwr.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_pwr.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_rcc.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_rcc.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_rtc.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_rtc.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_spi.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_spi.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_tim.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_tim.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_usart.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_usart.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"
Peripheral/src/ch32v20x_wwdg.o: e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/src/ch32v20x_wwdg.c
	@	riscv-none-embed-gcc -march=rv32imacxw -mabi=ilp32 -msmall-data-limit=8 -msave-restore -fmax-errors=20 -Os -fmessage-length=0 -fsigned-char -ffunction-sections -fdata-sections -fno-common -Wunused -Wuninitialized -g -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Debug" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Core" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/TKEY/User" -I"e:/AndroidProject/SmartHomeApp/embedded_code/ch32x203c/SRC/Peripheral/inc" -std=gnu99 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@)" -c -o "$@" "$<"

