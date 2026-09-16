/********************************** (C) COPYRIGHT *******************************
 * File Name          : main.c
 * Author             : WCH
 * Version            : V1.5.0
 * Date               : 2026/07/23
 * Description        : 双通道触摸检测 + GPIO输出 + UART上报到ESP8266
 *********************************************************************************
 * 参考EVT111 TOUCHKEY示例结构修改
 * 硬件连接:
 *   PA0 - touchkey1 -> ADC_Channel_0
 *   PA1 - touchkey2 -> ADC_Channel_1
 *   PB0 - GPIO输出1 (TK1触摸时高电平)
 *   PB1 - GPIO输出2 (TK2触摸时高电平)
 *   PA9 - USART1_TX -> 电脑调试串口 (USB转串口)
 *   PA10 - USART1_RX -> 电脑调试串口
 *   PB10 - USART3_TX -> ESP8266 RX (GPIO0)
 *   PB11 - USART3_RX -> ESP8266 TX (GPIO1)
 *
 * 触摸判断: 值越小 = 触摸 (与EVT111一致)
 * GPIO逻辑: 两个独立if，后一个覆盖前一个
 *   触摸TK1 -> PB0=1, PB1=0
 *   触摸TK2 -> PB0=0, PB1=1
 *   同时触摸 -> PB0=0, PB1=1 (TK2覆盖)
 *   都未触摸 -> PB0=0, PB1=0
 *
 * USART1(115200): 电脑调试输出
 * USART3(4800): V1:<val1>,V2:<val2>,S1:<0/1>,S2:<0/1>\r\n -> ESP8266
 *********************************************************************************/

#include "debug.h"

#define TOUCH_THRESHOLD  400
#define LED_PIN          GPIO_Pin_0
#define LED_PIN1         GPIO_Pin_1
#define LED_PORT         GPIOB

/*********************************************************************
 * @fn      USART3_Init
 * @brief   初始化USART3 (PB10=TX, PB11=RX) 用于连接ESP8266
 *********************************************************************/
void USART3_Init(u32 bound)
{
	GPIO_InitTypeDef GPIO_InitStructure;
	USART_InitTypeDef USART_InitStructure;

	/* 使能时钟 */
	RCC_APB1PeriphClockCmd(RCC_APB1Periph_USART3, ENABLE);
	RCC_APB2PeriphClockCmd(RCC_APB2Periph_GPIOB, ENABLE);

	/* PB10 - USART3_TX 复用推挽输出 */
	GPIO_InitStructure.GPIO_Pin = GPIO_Pin_10;
	GPIO_InitStructure.GPIO_Mode = GPIO_Mode_AF_PP;
	GPIO_InitStructure.GPIO_Speed = GPIO_Speed_50MHz;
	GPIO_Init(GPIOB, &GPIO_InitStructure);

	/* PB11 - USART3_RX 浮空输入 */
	GPIO_InitStructure.GPIO_Pin = GPIO_Pin_11;
	GPIO_InitStructure.GPIO_Mode = GPIO_Mode_IN_FLOATING;
	GPIO_Init(GPIOB, &GPIO_InitStructure);

	/* USART3 配置 */
	USART_InitStructure.USART_BaudRate = bound;
	USART_InitStructure.USART_WordLength = USART_WordLength_8b;
	USART_InitStructure.USART_StopBits = USART_StopBits_1;
	USART_InitStructure.USART_Parity = USART_Parity_No;
	USART_InitStructure.USART_HardwareFlowControl = USART_HardwareFlowControl_None;
	USART_InitStructure.USART_Mode = USART_Mode_Rx | USART_Mode_Tx;
	USART_Init(USART3, &USART_InitStructure);

	USART_Cmd(USART3, ENABLE);
}

/*********************************************************************
 * @fn      USART3_SendString
 * @brief   通过USART3发送字符串到ESP8266
 *********************************************************************/
void USART3_SendString(char *str)
{
	while(*str)
	{
		while(USART_GetFlagStatus(USART3, USART_FLAG_TXE) == RESET);
		USART_SendData(USART3, *str++);
	}
}

/*********************************************************************
 * @fn      Touch_Key_Init
 * @brief   初始化触摸按键(PA0+PA1) + GPIO输出(PB0+PB1)
 *********************************************************************/
void Touch_Key_Init(void)
{
	GPIO_InitTypeDef GPIO_InitStructure={0};
	ADC_InitTypeDef ADC_InitStructure={0};

	RCC_APB2PeriphClockCmd(RCC_APB2Periph_GPIOA | RCC_APB2Periph_GPIOB |
                           RCC_APB2Periph_ADC1, ENABLE);
	RCC_ADCCLKConfig(RCC_PCLK2_Div8);

	/* PA0 + PA1 模拟输入 (触摸) */
	GPIO_InitStructure.GPIO_Pin = GPIO_Pin_0 | GPIO_Pin_1;
	GPIO_InitStructure.GPIO_Mode = GPIO_Mode_AIN;
	GPIO_Init(GPIOA, &GPIO_InitStructure);

	/* PB0 + PB1 推挽输出 */
	GPIO_InitStructure.GPIO_Pin = LED_PIN | LED_PIN1;
	GPIO_InitStructure.GPIO_Mode = GPIO_Mode_Out_PP;
	GPIO_InitStructure.GPIO_Speed = GPIO_Speed_50MHz;
	GPIO_Init(LED_PORT, &GPIO_InitStructure);

	GPIO_SetBits(LED_PORT, LED_PIN | LED_PIN1);

	/* ADC初始化 */
	ADC_InitStructure.ADC_Mode = ADC_Mode_Independent;
	ADC_InitStructure.ADC_ScanConvMode = DISABLE;
	ADC_InitStructure.ADC_ContinuousConvMode = DISABLE;
	ADC_InitStructure.ADC_ExternalTrigConv = ADC_ExternalTrigConv_None;
	ADC_InitStructure.ADC_DataAlign = ADC_DataAlign_Right;
	ADC_InitStructure.ADC_NbrOfChannel = 1;
	ADC_Init(ADC1, &ADC_InitStructure);

	ADC_Cmd(ADC1, ENABLE);
	TKey1->CTLR1 |= (1<<26)|(1<<24);
}

/*********************************************************************
 * @fn      Touch_Key_Adc
 * @brief   读取触摸通道原始值
 *********************************************************************/
u16 Touch_Key_Adc(u8 ch)
{
	ADC_RegularChannelConfig(ADC1, ch, 1, ADC_SampleTime_7Cycles5);
	TKey1->IDATAR1 = 0x10;
	TKey1->RDATAR = 0x8;
	ADC_SoftwareStartConvCmd(ADC1, ENABLE);
	while(!ADC_GetFlagStatus(ADC1, ADC_FLAG_EOC));
	ADC_SoftwareStartConvCmd(ADC1, DISABLE);
	return (uint16_t)TKey1->RDATAR;
}

/*********************************************************************
 * @fn      main
 * @brief   双通道触摸 + GPIO + UART上报
 *********************************************************************/
int main(void)
{
	u16 tk1_val, tk2_val;
	u8  tk1_sta, tk2_sta;
	u32 print_cnt = 0;
	char buf[64];

	SystemCoreClockUpdate();
	Delay_Init();
	USART_Printf_Init(115200);  // USART1 -> 电脑调试

	printf("\r\n===== CH32X203C TouchKey =====\r\n");
	printf("SystemClk:%d\r\n", SystemCoreClock);
	printf("Threshold:%d\r\n", TOUCH_THRESHOLD);
	printf("USART3(4800) -> ESP-01S (PB10->RX, PB11->TX)\r\n");
	printf("==============================\r\n");

	Touch_Key_Init();
	USART3_Init(4800);  // USART3 -> ESP8266 (4800波特率, ESP8266 SoftwareSerial在高波特率下不可靠)

	while(1)
	{
		tk1_val = Touch_Key_Adc(ADC_Channel_0);
		tk2_val = Touch_Key_Adc(ADC_Channel_1);

		tk1_sta = (tk1_val < TOUCH_THRESHOLD) ? 1 : 0;
		tk2_sta = (tk2_val < TOUCH_THRESHOLD) ? 1 : 0;

		/* 两个独立if, 后一个覆盖前一个 (与EVT111一致) */
		if(tk1_sta)
		{
			GPIO_SetBits(LED_PORT, LED_PIN);
			GPIO_ResetBits(LED_PORT, LED_PIN1);
		}
		if(tk2_sta)
		{
			GPIO_ResetBits(LED_PORT, LED_PIN);
			GPIO_SetBits(LED_PORT, LED_PIN1);
		}
		/* 都未触摸时全部置低 */
		if(!tk1_sta && !tk2_sta)
		{
			GPIO_ResetBits(LED_PORT, LED_PIN | LED_PIN1);
		}

		/* USART3 发送数据给ESP8266 */
		sprintf(buf, "V1:%d,V2:%d,S1:%d,S2:%d\r\n", tk1_val, tk2_val, tk1_sta, tk2_sta);
		USART3_SendString(buf);

		/* USART1 调试输出到电脑 */
		printf("V1:%d,V2:%d,S1:%d,S2:%d\r\n", tk1_val, tk2_val, tk1_sta, tk2_sta);

		print_cnt++;
		if(print_cnt >= 2)
		{
			print_cnt = 0;
			printf("TK1:%d(%s) TK2:%d(%s)\r\n",
				   tk1_val, tk1_sta ? "PRESSED" : "rel",
				   tk2_val, tk2_sta ? "PRESSED" : "rel");
		}

		Delay_Ms(500);
	}
}
