/*	TP Link Cloud TP-Link Dimming Switch Device Driver, Hubitat Version 1
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
	definition (name: "(Cloud) TP-Link Dimming Switch",
				namespace: "davegut",
				author: "Dave Gutheinz") {
		capability "Switch"
		capability "Sensor"
		capability "Actuator"
        command "refresh"
		capability "Switch Level"
	}

	def rates = [:]
	rates << ["1" : "Refresh every minutes (Not Recommended)"]
	rates << ["5" : "Refresh every 5 minutes"]
	rates << ["10" : "Refresh every 10 minutes"]
	rates << ["15" : "Refresh every 15 minutes"]
	rates << ["30" : "Refresh every 30 minutes (Recommended)"]

	preferences {
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
		case "1":
			runEvery1Minute(refresh)
			log.info "Refresh Scheduled for every minute"
			break
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
	runIn(5, refresh)
}

void uninstalled() {
	def alias = device.label
	log.debug "Removing device ${alias} with DNI = ${device.deviceNetworkId}"
	parent.removeChildDevice(alias, device.deviceNetworkId)
}

//	===== Basic Plug Control/Status =====
def on() {
	sendCmdtoServer('{"system":{"set_relay_state":{"state": 1}}}', "deviceCommand", "commandResponse")
}

def off() {
	sendCmdtoServer('{"system":{"set_relay_state":{"state": 0}}}', "deviceCommand", "commandResponse")
}

def setLevel(percentage) {
	percentage = percentage as int
    if (percentage == 0) {
    	percentage = 1
    }
	sendCmdtoServer("""{"smartlife.iot.dimmer":{"set_brightness":{"brightness":${percentage}}}}""", "deviceCommand", "commandResponse")
}

def refresh(){
	sendCmdtoServer('{"system":{"get_sysinfo":{}}}', "deviceCommand", "refreshResponse")
}

def commandResponse(cmdResponse) {
	refresh()
}

def refreshResponse(cmdResponse){
	def onOff = cmdResponse.system.get_sysinfo.relay_state
	if (onOff == 1) {
		onOff = "on"
	} else {
		onOff = "off"
	}
	sendEvent(name: "switch", value: onOff)
	def level = "0"
	level = cmdResponse.system.get_sysinfo.brightness
 	sendEvent(name: "level", value: level)
	log.info "${device.name} ${device.label}: Power: ${onOff} / Dimmer Level: ${level}%"
}

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

		case "refreshResponse":
			refreshResponse(cmdResponse)
			break

		default:
			log.debug "at default"
	}
}

//	----- CHILD / PARENT INTERCHANGE TASKS -----
def syncAppServerUrl(newAppServerUrl) {
	updateDataValue("appServerUrl", newAppServerUrl)
		log.info "Updated appServerUrl for ${device.name} ${device.label}"
}