/*	TP Link Cloud SoftWhite Bulbs Device Driver, Hubitat Version 1
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
	definition (name: "((Cloud) TP-Link SoftWhite Bulb",
				namespace: "davegut",
				author: "Dave Gutheinz") {
		capability "Switch"
		capability "Switch Level"
		capability "Sensor"
		capability "Actuator"
        command "refresh"
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
	runIn(2, refresh)
}

void uninstalled() {
	def alias = device.label
	log.debug "Removing device ${alias} with DNI = ${device.deviceNetworkId}"
	parent.removeChildDevice(alias, device.deviceNetworkId)
}

//	===== Basic Bulb Control/Status =====
def on() {
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":1,"transition_period":${state.transTime}}}}""", "deviceCommand", "commandResponse")
}

def off() {
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":0,"transition_period":${state.transTime}}}}""", "deviceCommand", "commandResponse")
}

def setLevel(percentage) {
	percentage = percentage as int
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"brightness":${percentage},"transition_period":${state.transTime}}}}""", "deviceCommand", "commandResponse")
}

def refresh(){
	sendCmdtoServer('{"system":{"get_sysinfo":{}}}', "deviceCommand", "commandResponse")
}

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
	log.info "$device.name $device.label: Power: ${onOff} / Brightness: ${level}%"
	sendEvent(name: "switch", value: onOff)
 	sendEvent(name: "level", value: level)
}

//	===== Send the Command =====
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
		default:
			log.info "Interface Error.  See SmartApp and Device error message."
	}
}

//	===== Child / Parent Interchange =====
def syncAppServerUrl(newAppServerUrl) {
	updateDataValue("appServerUrl", newAppServerUrl)
		log.info "Updated appServerUrl for ${device.name} ${device.label}"
}