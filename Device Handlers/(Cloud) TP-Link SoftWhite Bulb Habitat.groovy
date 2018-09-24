/*	Kasa Cloud SoftWhite Bulbs Device Driver, Hubitat Version 3
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
2018-09-30	Update to Version 3.  Adds capabilities and
			equivalency with I/O from Generic Zigbee RGBW
			bulbs.
========================================================*/

metadata {
	definition (name: "(Cloud) TP-Link SoftWhite Bulb",
				namespace: "davegut",
				author: "Dave Gutheinz") {
		capability "Actuator"
        capability "Refresh"
		capability "Switch"
		capability "Switch Level"
        capability "Light"
	}
    
	def rates = [:]
	rates << ["5" : "Refresh every 5 minutes"]
	rates << ["10" : "Refresh every 10 minutes"]
	rates << ["15" : "Refresh every 15 minutes"]
	rates << ["30" : "Refresh every 30 minutes"]

    preferences {
        input name: "transitionTime", type: "enum", description: "", title: "Transition time", options: [[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 1000
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
    log.info "updated..."
    log.info "Transition time set to ${transitionTime}"
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
	runIn(2, refresh)
}

void uninstalled() {
	def alias = device.label
	log.debug "Removing device ${alias} with DNI = ${device.deviceNetworkId}"
	parent.removeChildDevice(alias, device.deviceNetworkId)
}

//	===== Basic Bulb Control/Status =====
def on() {
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":1,"transition_period":${transitionTime}}}}""", "deviceCommand", "commandResponse")
}

def off() {
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":0,"transition_period":${transitionTime}}}}""", "deviceCommand", "commandResponse")
}

def setLevel() {
	log.error "$device.name $device.label: Null entries in Set Level"
}

def setLevel(percentage) {
    setLevel(percentage, transitionTime)
}

def setLevel(percentage, rate) {
    if (percentage < 0 || percentage > 100) {
        log.error "$device.name $device.label: Entered brightness is above 100%"
        return
    }
//	Rate is anticiated in seconds.  Convert to msec for Kasa Bulbs
	percentage = percentage as int
    rate = rate.toBigDecimal()
    def scaledRate = (rate * 1000).toInteger()
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"brightness":${percentage},"transition_period":${scaledRate}}}}""", "deviceCommand", "commandResponse")
}

def refresh(){
	sendCmdtoServer('{"system":{"get_sysinfo":{}}}', "deviceCommand", "commandResponse")
}

//	Parse the Bulb Return (only one response possible)
def commandResponse(cmdResponse){
	def status
	def respType = cmdResponse.toString().substring(1,7)
    if (respType == "smartl") {
		status =  cmdResponse["smartlife.iot.smartbulb.lightingservice"]["transition_light_state"]
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
	log.info "$device.name $device.label: Power: ${onOff} / Brightness: ${level}%"
	sendEvent(name: "switch", value: onOff)
    if (onOff == "off") return
 	sendEvent(name: "level", value: level)
}

//	===== Send the Command =====
private sendCmdtoServer(command, hubCommand, action) {
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
	commandResponse(cmdResponse)
}

//	===== Child / Parent Interchange =====
//	Use Parent for Comms to provide single error handling with cloud.
def syncAppServerUrl(newAppServerUrl) {
	updateDataValue("appServerUrl", newAppServerUrl)
		log.info "Updated appServerUrl for ${device.name} ${device.label}"
}
