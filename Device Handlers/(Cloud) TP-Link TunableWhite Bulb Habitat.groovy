/*	TP Link Cloud TP-Link TunableWhite Bulb Emon Device Driver, Hubitat Version 1
	Copyright 2018 Dave Gutheinz

Licensed under the Apache License, Version 2.0(the "License");
you may not use this  file except in compliance with the
License. You may obtain a copy of the License at:

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, 
software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
either express or implied. See the License for the specific 
language governing permissions and limitations under the 
License.

Discalimer:  These drivers and associated application are in
no way sanctioned or supported by TP-Link.  All  development
is based upon open-source data on the TP-Link devices; 
primarily various users on GitHub.com.

===== History ============================================
2018-05-02	Update to Version 2
========================================================*/

metadata {
	definition (name: "(Cloud) TP-Link TunableWhite Bulb Emon",
				namespace: "davegut",
				author: "Dave Gutheinz") {
		capability "Switch"
		capability "Switch Level"
		capability "Sensor"
		capability "Actuator"
		capability "Color Temperature"
		command "setModeNormal"
		command "setModeCircadian"
		attribute "bulbMode", "string"
        command "refresh"
        command "poll"
		command "getPower"
		capability "Energy Meter"
		command "getEnergyStats"
		attribute "monthTotalE", "string"
		attribute "monthAvgE", "string"
		attribute "weekTotalE", "string"
		attribute "weekAvgE", "string"
	}

	def rates = [:]
	rates << ["5" : "Refresh every 5 minutes"]
	rates << ["10" : "Refresh every 10 minutes"]
	rates << ["15" : "Refresh every 15 minutes"]
	rates << ["30" : "Refresh every 30 minutes"]

	preferences {
		input name: "lightTransTime", type: "number", title: "Lighting Transition Time (seconds)", options: rates, description: "0 to 60 seconds", required: false
		input name: "refreshRate", type: "enum", title: "Refresh Rate", options: rates, description: "Select Refresh Rate", required: false
	}
}

//	===== Update when installed or setting changed =====
def installed() {
	update()
}

def updated() {
	runIn(2, update)
}

def update() {
	unschedule()
	switch(refreshRate) {
		case "5":
			runEvery5Minutes(refresh)
			log.info "Refresh Scheduled for every 5 minutes"
			break
		case "10":
			runEvery10Minutes(refresh)
			log.info "Refresh Scheduled for every 10 minutes"
			break
		case "15":
			runEvery15Minutes(refresh)
			log.info "Refresh Scheduled for every 15 minutes"
			break
		default:
			runEvery30Minutes(refresh)
			log.info "Refresh Scheduled for every 30 minutes"
	}
	if (lightTransTime >= 0 && lightTransTime <= 60) {
		state.transTime = 1000 * lightTransTime
	} else {
		state.transTime = 5000
	}
	schedule("0 05 0 * * ?", setCurrentDate)
	schedule("0 10 0 * * ?", getEnergyStats)
	setCurrentDate()
	runIn(2, refresh)
	runIn(7, getEnergyStats)
}

void uninstalled() {
	if (state.installType == "Cloud") {
		def alias = device.label
		log.debug "Removing device ${alias} with DNI = ${device.deviceNetworkId}"
		parent.removeChildDevice(alias, device.deviceNetworkId)
	}
}

//	===== Basic Bulb Control/Status =====
def on() {
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":1,"transition_period":${state.transTime}}}}""", "deviceCommand", "commandResponse")
	runIn(2, getPower)
}

def off() {
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":0,"transition_period":${state.transTime}}}}""", "deviceCommand", "commandResponse")
	runIn(2, getPower)
}

def setLevel(percentage) {
	percentage = percentage as int
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"brightness":${percentage},"transition_period":${state.transTime}}}}""", "deviceCommand", "commandResponse")
	runIn(2, getPower)
}

def setColorTemperature(kelvin) {
	kelvin = kelvin as int
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"color_temp": ${kelvin},"hue":0,"saturation":0}}}""", "deviceCommand", "commandResponse")
	runIn(2, getPower)
}

def setModeNormal() {
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"mode":"normal"}}}""", "deviceCommand", "commandResponse")
}

def setModeCircadian() {
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"mode":"circadian"}}}""", "deviceCommand", "commandResponse")
	runIn(2, getPower)
}

def poll(){
	sendCmdtoServer('{"system":{"get_sysinfo":{}}}', "deviceCommand", "commandResponse")
}

def refresh(){
	sendCmdtoServer('{"system":{"get_sysinfo":{}}}', "deviceCommand", "commandResponse")
	runIn(2, getPower)
}

def commandResponse(cmdResponse){
	def status
	def respType = cmdResponse.toString().substring(0,9)
    if (respType == "{smartlif") {
		status = cmdResponse["smartlife.iot.smartbulb.lightingservice"]["transition_light_state"]
	} else {
		status = cmdResponse.system.get_sysinfo.light_state
	}
	def onOff = status.on_off
	if (onOff == 1) {
		onOff = "on"
	} else {
		onOff = "off"
		status = status.dft_on_state
	}
	def level = status.brightness
	def mode = status.mode
	def color_temp = status.color_temp
	log.info "$device.name $device.label: Power: ${onOff} / Brightness: ${level}% / Mode: ${mode} / Color Temp: ${color_temp}K"
	sendEvent(name: "switch", value: onOff)
 	sendEvent(name: "level", value: level)
	sendEvent(name: "bulbMode", value: mode)
	sendEvent(name: "colorTemperature", value: color_temp)
}

//	===== Get Current Energy Data =====
def getPower(){
	sendCmdtoServer("""{"smartlife.iot.common.emeter":{"get_realtime":{}}}""", "deviceCommand", "energyMeterResponse")
	runIn(5, getConsumption)
}

def energyMeterResponse(cmdResponse) {
	def realtime = cmdResponse["smartlife.iot.common.emeter"]["get_realtime"]
	if (realtime.power == null) {
		state.powerScale = "power_mw"
		state.energyScale = "energy_wh"
	} else {
		state.powerScale = "power"
		state.energyScale = "energy"
	}
	def powerConsumption = realtime."${state.powerScale}"
	if (state.powerScale == "power_mw") {
		powerConsumption = Math.round(powerConsumption/10) / 100
	} else {
		powerConsumption = Math.round(100*powerConsumption) / 100
	}
	sendEvent(name: "power", value: powerConsumption)
	log.info "$device.name $device.label: Updated CurrentPower to $powerConsumption"
}

//	===== Get Today's Consumption =====
def getConsumption(){
	sendCmdtoServer("""{"smartlife.iot.common.emeter":{"get_daystat":{"month": ${state.monthToday}, "year": ${state.yearToday}}}}""", "emeterCmd", "useTodayResponse")
}

def useTodayResponse(cmdResponse) {
	def wattHrToday
	def wattHrData
	def dayList = cmdResponse["smartlife.iot.common.emeter"]["get_daystat"].day_list
	for (int i = 0; i < dayList.size(); i++) {
		wattHrData = dayList[i]
		if(wattHrData.day == state.dayToday) {
			wattHrToday = wattHrData."${state.energyScale}"
 		}
	}
	if (state.powerScale == "power") {
		wattHrToday = Math.round(1000*wattHrToday)
	}
	sendEvent(name: "energy", value: wattHrToday)
	log.info "$device.name $device.label: Updated Usage Today to ${wattHrToday}"
}

//	===== Get Weekly and Monthly Stats =====
def getEnergyStats() {
	state.monTotEnergy = 0
	state.monTotDays = 0
	state.wkTotEnergy = 0
	state.wkTotDays = 0
	sendCmdtoServer("""{"smartlife.iot.common.emeter":{"get_daystat":{"month": ${state.monthToday}, "year": ${state.yearToday}}}}""", "emeterCmd", "engrStatsResponse")
	runIn(4, getPrevMonth)
}

def getPrevMonth() {
	def prevMonth = state.monthStart
	if (state.monthToday == state.monthStart) {
		//	If all of the data is in this month, do not request previous month.
		//	This will occur when the current month is 31 days.
		return
	} else if (state.monthToday - 2 == state.monthStart) {
		//	If the start month is 2 less than current, we must handle
		//	the data to get a third month - January.
		state.handleFeb = "yes"
		prevMonth = prevMonth + 1
		runIn(4, getJan)
	}
	sendCmdtoServer("""{"smartlife.iot.common.emeter":{"get_daystat":{"month": ${prevMonth}, "year": ${state.yearStart}}}}""", "emeterCmd", "engrStatsResponse")
}

def getJan() {
//	Gets January data on March 1 and 2.  Only accessed if current month = 3
//	and start month = 1
	sendCmdtoServer("""{"smartlife.iot.common.emeter":{"get_daystat":{"month": ${state.monthStart}, "year": ${state.yearStart}}}}""", "emeterCmd", "engrStatsResponse")
}

def engrStatsResponse(cmdResponse) {
/*	
	This method parses up to two energy status messages from the device,
	adding the energy for the previous 30 days and week, ignoring the
	current day.  It then calculates the 30 and 7 day average formatted
	in kiloWattHours to two decimal places.
*/
	def dayList = cmdResponse["smartlife.iot.common.emeter"]["get_daystat"].day_list
	if (!dayList[0]) {
		log.info "$device.name $device.label: Month has no energy data."
		return
	}
	def monTotEnergy = state.monTotEnergy
	def wkTotEnergy = state.wkTotEnergy
	def monTotDays = state.monTotDays
	def wkTotDays = state.wkTotDays
    def startDay = state.dayStart
	def dataMonth = dayList[0].month
	if (dataMonth == state.monthToday) {
		for (int i = 0; i < dayList.size(); i++) {
			def energyData = dayList[i]
			monTotEnergy += energyData."${state.energyScale}"
			monTotDays += 1
			if (state.dayToday < 8 || energyData.day >= state.weekStart) {
				wkTotEnergy += energyData."${state.energyScale}"
				wkTotDays += 1
			}
			if(energyData.day == state.dayToday) {
				monTotEnergy -= energyData."${state.energyScale}"
				wkTotEnergy -= energyData."${state.energyScale}"
				monTotDays -= 1
				wkTotDays -= 1
			}
		}
	} else if (state.handleFeb == "yes" && dataMonth == 2) {
    	startDay = 1
		for (int i = 0; i < dayList.size(); i++) {
			def energyData = dayList[i]
			if (energyData.day >= startDay) {
				monTotEnergy += energyData."${state.energyScale}"
				monTotDays += 1
			}
			if (energyData.day >= state.weekStart && state.dayToday < 8) {
				wkTotEnergy += energyData."${state.energyScale}"
				wkTotDays += 1
			}
		}
	} else if (state.handleFeb == "yes" && dataMonth == 1) {
		for (int i = 0; i < dayList.size(); i++) {
			def energyData = dayList[i]
			if (energyData.day >= startDay) {
				monTotEnergy += energyData."${state.energyScale}"
				monTotDays += 1
			}
			state.handleFeb = ""
		}
	} else {
		for (int i = 0; i < dayList.size(); i++) {
			def energyData = dayList[i]
			if (energyData.day >= startDay) {
				monTotEnergy += energyData."${state.energyScale}"
				monTotDays += 1
			}
			if (energyData.day >= state.weekStart && state.dayToday < 8) {
				wkTotEnergy += energyData."${state.energyScale}"
				wkTotDays += 1
			}
		}
	}
	state.monTotDays = monTotDays
	state.monTotEnergy = monTotEnergy
	state.wkTotEnergy = wkTotEnergy
	state.wkTotDays = wkTotDays
	log.info "$device.name $device.label: Update 7 and 30 day energy consumption statistics"
    if (monTotDays == 0) {
    	//	Aviod divide by zero on 1st of month
    	monTotDays = 1
        wkTotDays = 1 
	}
	def monAvgEnergy =monTotEnergy/monTotDays
	def wkAvgEnergy = wkTotEnergy/wkTotDays
	if (state.powerScale == "power_mw") {
		monAvgEnergy = Math.round(monAvgEnergy/10)/100
		wkAvgEnergy = Math.round(wkAvgEnergy/10)/100
		monTotEnergy = Math.round(monTotEnergy/10)/100
		wkTotEnergy = Math.round(wkTotEnergy/10)/100
	} else {
		monAvgEnergy = Math.round(100*monAvgEnergy)/100
		wkAvgEnergy = Math.round(100*wkAvgEnergy)/100
		monTotEnergy = Math.round(100*monTotEnergy)/100
		wkTotEnergy = Math.round(100*wkTotEnergy)/100
	}
	sendEvent(name: "monthTotalE", value: monTotEnergy)
	sendEvent(name: "monthAvgE", value: monAvgEnergy)
	sendEvent(name: "weekTotalE", value: wkTotEnergy)
	sendEvent(name: "weekAvgE", value: wkAvgEnergy)
}

//	===== Obtain Week and Month Data =====
def setCurrentDate() {
	sendCmdtoServer('{"smartlife.iot.common.timesetting":{"get_time":null}}', "deviceCommand", "currentDateResponse")
}

def currentDateResponse(cmdResponse) {
	def currDate =  cmdResponse["smartlife.iot.common.timesetting"]["get_time"]
	state.dayToday = currDate.mday.toInteger()
	state.monthToday = currDate.month.toInteger()
	state.yearToday = currDate.year.toInteger()
	def dateToday = Date.parse("yyyy-MM-dd", "${currDate.year}-${currDate.month}-${currDate.mday}")
	def monStartDate = dateToday - 30
	def wkStartDate = dateToday - 7
	state.dayStart = monStartDate[Calendar.DAY_OF_MONTH].toInteger()
	state.monthStart = monStartDate[Calendar.MONTH].toInteger() + 1
	state.yearStart = monStartDate[Calendar.YEAR].toInteger()
	state.weekStart = wkStartDate[Calendar.DAY_OF_MONTH].toInteger()
}

//	===== Send the Command to the Cloud or Bridge =====
private sendCmdtoServer(command, hubCommand, action){
	def appServerUrl = getDataValue("appServerUrl")
	def deviceId = getDataValue("deviceId")
	def cmdResponse = parent.sendDeviceCmd(appServerUrl, deviceId, command)
	String cmdResp = cmdResponse.toString()
	if (cmdResp.substring(0,5) == "ERROR"){
		def errMsg = cmdResp.substring(7,cmdResp.length())
		log.error "${device.name} ${device.label}: ${errMsg}"
		sendEvent(name: "switch", value: "commsError", descriptionText: errMsg)
		sendEvent(name: "deviceError", value: errMsg)
		action = ""
	} else {
		sendEvent(name: "deviceError", value: "OK")
	}
	actionDirector(action, cmdResponse)
}

def actionDirector(action, cmdResponse) {
	switch(action) {
		case "commandResponse":
			commandResponse(cmdResponse)
			break
		case "energyMeterResponse":
			energyMeterResponse(cmdResponse)
			break
		case "useTodayResponse":
			useTodayResponse(cmdResponse)
			break
		case "currentDateResponse":
			currentDateResponse(cmdResponse)
			break
		case "engrStatsResponse":
			engrStatsResponse(cmdResponse)
			break
		default:
			log.info "Interface Error.  See SmartApp and Device error message."
	}
}

//	===== Child / Parent Interchange =====
def syncAppServerUrl(newAppServerUrl) {
	updateDataValue("appServerUrl", newAppServerUrl)
		log.info "Updated appServerUrl for ${device.name} ${device.label}"
}
