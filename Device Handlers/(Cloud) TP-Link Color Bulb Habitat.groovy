/*	Kasa Cloud Color Bulbs Device Driver, Hubitat Version 3
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
	definition (name: "(Cloud) TP-Link Color Bulb",
				namespace: "davegut",
				author: "Dave Gutheinz") {
		capability "Actuator"
		capability "Color Control"
		capability "Color Temperature"
        capability "Refresh"
		capability "Switch"
		capability "Switch Level"
        capability "Light"
        capability "ColorMode"

        attribute "colorName", "string"
         
        command "toggleCircadianState"
		attribute "circadianState", "string"
	}


	def rates = [:]
	rates << ["5" : "Refresh every 5 minutes"]
	rates << ["10" : "Refresh every 10 minutes"]
	rates << ["15" : "Refresh every 15 minutes"]
	rates << ["30" : "Refresh every 30 minutes"]

    preferences {
        input name: "transitionTime", type: "enum", description: "", title: "Transition time", options: [[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 1000
        input name: "hiRezHue", type: "bool", title: "Enable Hue in degrees (0-360)", defaultValue: false
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
    log.info "Hue in degrees is: ${hiRezHue == true}"
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
	percentage = percentage as int
    rate = rate.toInteger()
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"brightness":${percentage},"transition_period":${rate}}}}""", "deviceCommand", "commandResponse")
}

def setColorTemperature(kelvin) {
    if (kelvin == null) kelvin = state.lastColorTemp
	kelvin = kelvin as int
    if (kelvin < 2500 || kelvin > 9000) {
        log.error "$device.name $device.label: Entered color temperature out of range!"
        return
    }
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"color_temp": ${kelvin},"hue":0,"saturation":0}}}""", "deviceCommand", "commandResponse")
}

def toggleCircadianState() {
    def cirState = device.currentValue("circadianState")
    if (cirState == "normal") {
        cirState = "circadian"
    }else {
        cirState = "normal"
    }
    sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"mode":"${cirState}"}}}""", "deviceCommand", "commandResponse")
}

def setHue(hue) {
    if (hue == null) hue = state.lastHue
    saturation = state.lastSaturation
    setColor([hue: hue, saturation: saturation])
}

def setSaturation(saturation) {
    if (saturation == null) hue = state.lastSaturation
    hue = state.lastHue
    setColor([hue: hue, saturation: saturation])
}

def setColor(color) {
    if (color == null) color = [hue: state.lastHue, saturation: state.lastSaturation]
    def hue = color.hue.toInteger()
//  if lowRezHue, adjust hue to 0...360
    if (!hiRezHue) hue = (hue * 3.6) as int
	def saturation = color.saturation as int
    if (hue < 0 || hue > 360 || saturation < 0 || saturation > 100) {
        log.error "$device.name $device.label: Entered hue or saturation out of range!"
        return
    }
	sendCmdtoServer("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"color_temp":0,"hue":${hue},"saturation":${saturation},"transition_period":${transitionTime}}}}""", "deviceCommand", "commandResponse")
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
	def hue = status.hue
//	If input is lowRezHue, adjust hue to 0...100.
    if (!hiRezHue) hue = (hue / 3.6) as int
	def saturation = status.saturation
	log.info "$device.name $device.label: Power: ${onOff} / Brightness: ${level}% / Mode: ${mode} / Color Temp: ${color_temp}K / Hue: ${hue} / Saturation: ${saturation}"
	sendEvent(name: "switch", value: onOff)
    if (onOff == "off") return
 	sendEvent(name: "level", value: level)
	sendEvent(name: "circadianState", value: mode)
	sendEvent(name: "colorTemperature", value: color_temp)
	sendEvent(name: "hue", value: hue)
	sendEvent(name: "saturation", value: saturation)
/*  Bulb sets color temp to 0 if either hue or saturation > 0
	If color_temp > 0, hue and saturation are set to 0.*/
    if (color_temp.toInteger() == 0) {
        state.lastHue = hue
        state.lastSaturation = saturation
        setRgbData(hue)
    } else {
        state.lastColorTemp = color_temp
        setColorTempData(color_temp)
    }
}

def setColorTempData(temp){
    def value = temp.toInteger()
    def genericName
    if (value < 2400) genericName = "Sunrise"
    else if (value < 2800) genericName = "Incandescent"
    else if (value < 3300) genericName = "Soft White"
    else if (value < 3500) genericName = "Warm White"
    else if (value < 4150) genericName = "Moonlight"
    else if (value <= 5000) genericName = "Horizon"
    else if (value < 5500) genericName = "Daylight"
    else if (value < 6000) genericName = "Electronic"
    else if (value <= 6500) genericName = "Skylight"
    else if (value < 20000) genericName = "Polar"
	descriptionText = "${device.getDisplayName()} Color Mode is CT"
	log.info "${descriptionText}"
 	sendEvent(name: "colorMode", value: "CT" ,descriptionText: descriptionText)
    def descriptionText = "${device.getDisplayName()} color is ${genericName}"
	log.info "${descriptionText}"
    sendEvent(name: "colorName", value: genericName ,descriptionText: descriptionText)
}

def setRgbData(hue){
    def colorName
    hue = hue.toInteger()
    switch (hue.toInteger()){
        case 0..15: colorName = "Red"
            break
        case 16..45: colorName = "Orange"
            break
        case 46..75: colorName = "Yellow"
            break
        case 76..105: colorName = "Chartreuse"
            break
        case 106..135: colorName = "Green"
            break
        case 136..165: colorName = "Spring"
            break
        case 166..195: colorName = "Cyan"
            break
        case 196..225: colorName = "Azure"
            break
        case 226..255: colorName = "Blue"
            break
        case 256..285: colorName = "Violet"
            break
        case 286..315: colorName = "Magenta"
            break
        case 316..345: colorName = "Rose"
            break
        case 346..360: colorName = "Red"
            break
    }
	descriptionText = "${device.getDisplayName()} Color Mode is RGB"
	log.info "${descriptionText}"
 	sendEvent(name: "colorMode", value: "RGB" ,descriptionText: descriptionText)
    def descriptionText = "${device.getDisplayName()} color is ${colorName}"
	log.info "${descriptionText}"
    sendEvent(name: "colorName", value: colorName ,descriptionText: descriptionText)
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
