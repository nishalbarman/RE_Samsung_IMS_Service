//SPDX-License-Identifier: GPL-2.0
package me.phh.ims

import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.telecom.TelecomManager
import android.telephony.Rlog
import android.telephony.ims.ImsCallProfile
import android.telephony.ims.ImsCallSessionListener
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsStreamMediaProfile
import android.telephony.ims.feature.ImsFeature
import android.telephony.ims.stub.ImsCallSessionImplBase
import android.telephony.ims.stub.ImsMultiEndpointImplBase
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE
import android.telephony.ims.stub.ImsSmsImplBase
import android.telephony.ims.stub.ImsUtImplBase
import me.phh.sip.SipHandler
import me.phh.sip.randomBytes
import me.phh.sip.toHex

// frameworks/base/telephony/java/android/telephony/ims/feature/MmTelFeature.java
// We extend it through java once because kotlin cannot override
// changeEnabledCapabilities that has a protected (CapabilityCallbackProxy)
// argument. See this stackoverflow link for why we cannot do it directly:
// https://stackoverflow.com/questions/49284094/inheritance-from-java-class-with-a-public-method-accepting-a-protected-class-in/49287402#49287402
class PhhMmTelFeature(val slotId: Int) : android.telephony.ims.feature.PhhMmTelFeatureProtected(slotId) {
    companion object {
        private const val TAG = "PHH MmTelFeature"
    }

    val imsSms = PhhImsSms(slotId)
    lateinit var sipHandler: SipHandler
    var outgoingCallListener: ImsCallSessionListener? = null

    override fun createCallProfile(callSessionType: Int, callType: Int): ImsCallProfile {
        Rlog.d(TAG, "$slotId createCallProfile $callSessionType $callType")
        // check why not called
        // figure out RilHolder.INSTANCE.getRadios(mSlotId).setImsCfg ? Probably only required
        // if we leave ims to the radio...
        return ImsCallProfile(callSessionType, callType)
    }
    override fun createCallSession(profile: ImsCallProfile): ImsCallSessionImplBase {
        Rlog.d(TAG, "$slotId createCallSession")
        return object: ImsCallSessionImplBase() {
            private val mCallId = randomBytes(12).toHex()
            lateinit var mListener: ImsCallSessionListener
            var mState = State.IDLE
            override fun getCallId(): String {
                return mCallId
            }

            override fun close() {
                Rlog.d(TAG, "Closing call")
            }

            override fun accept(callType: Int, profile: ImsStreamMediaProfile) {
                Rlog.d(TAG, "Accepting call with callType $callType profile $profile")
            }

            override fun isInCall(): Boolean {
                return true
            }

            override fun start(callee: String, profile: ImsCallProfile) {
                Rlog.d(TAG, "Starting call with $callee profile $profile")
                mState = State.INITIATED
                sipHandler.onOutgoingCallProgressing = {
                    Rlog.d(TAG, "Outgoing call progressing")
                    mState = State.NEGOTIATING
                    mListener.callSessionProgressing(profile.mediaProfile)
                }
                sipHandler.onOutgoingCallStarted = {
                    Rlog.d(TAG, "Outgoing call started")
                    mState = State.ESTABLISHED
                    mListener.callSessionInitiated(profile)
                }
                sipHandler.call(callee)
            }

            override fun getState(): Int {
                return mState
            }

            override fun setListener(listener: ImsCallSessionListener) {
                Rlog.d(TAG, "Setting CallListener to $listener")
                mListener = listener
                outgoingCallListener = listener
            }

            override fun reject(reason: Int) {
                Rlog.d(TAG, "Rejecting call with reason $reason")
            }

            override fun terminate(reason: Int) {
                Rlog.d(TAG, "Terminating call with reason $reason")
                sipHandler.terminateCall()
                mState = State.TERMINATED
                mListener.callSessionTerminated(
                    ImsReasonInfo(
                        ImsReasonInfo.CODE_USER_TERMINATED,
                        0,
                        "Local hangup"
                    )
                )
            }
        }
    }

    fun getInstance(slotId: Int): PhhMmTelFeature {
        Rlog.d(TAG, "$slotId getInstance")
        return PhhMmTelFeature(slotId)
    }

    override fun getFeatureState(): Int {
        Rlog.d(TAG, "$slotId getFeatureState")
        // always ready for now... Also STATE_INITIALIZING, STATE_UNAVAILABLE
        return ImsFeature.STATE_READY
    }

    override fun getMultiEndpoint(): ImsMultiEndpointImplBase {
        Rlog.d(TAG, "$slotId getMultiEndpoint")
        return ImsMultiEndpointImplBase()
    }

    override fun getSmsImplementation(): ImsSmsImplBase {
        Rlog.d(TAG, "$slotId getSmsImplementation")
        return imsSms
    }

    override fun getUt(): ImsUtImplBase {
        Rlog.d(TAG, "$slotId getUt")
        return ImsUtImplBase()
    }

    override fun onFeatureReady() {
        Rlog.d(TAG, "$slotId onFeatureReady")
        publishCapabilities()
        if(this::sipHandler.isInitialized) return

        // call onRegistering first then
        // register SIP here and call onRegistered after .. register.
        val imsService = PhhImsService.Companion.instance!!
        sipHandler = SipHandler(imsService)
        sipHandler.imsFailureCallback = { imsService.getRegistration(slotId).onDeregistered(null) }
        sipHandler.imsReadyCallback = {
            imsService.getRegistration(slotId).onRegistered(REGISTRATION_TECH_LTE)
            publishCapabilities()
        }
        imsSms.sipHandler = sipHandler
        sipHandler.onSmsReceived = imsSms::onSmsReceived
        sipHandler.onSmsStatusReportReceived = imsSms::onSmsStatusReportReceived

        var incomingCallListener: ImsCallSessionListener? = null
        sipHandler.onIncomingCall = { handle: Object, from: String, extras: Map<String, String> -> 
            val callId = extras["call-id"] ?: randomBytes(12).toHex()
            val callerName = extras["caller-name"].orEmpty()
            val callerUri = extras["caller-uri"] ?: "tel:unknown"
            val digitsOnly = from.filter { it.isDigit() || it == '+' }
            val callerNumber = when {
                digitsOnly.isNotEmpty() -> digitsOnly
                callerName.isNotEmpty() -> callerName
                extras["raw-p-asserted-identity"] != null -> {
                    extras["raw-p-asserted-identity"]!!.filter { it.isDigit() || it == '+' }
                        .takeIf { it.isNotEmpty() } ?: "unknown"
                }
                extras["raw-from"] != null -> {
                    extras["raw-from"]!!.filter { it.isDigit() || it == '+' }
                        .takeIf { it.isNotEmpty() } ?: "unknown"
                }
                else -> "unknown"
            }
            val presentation = if (callerNumber != "unknown") {
                ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED
            } else {
                ImsCallProfile.OIR_PRESENTATION_UNKNOWN
            }
            val callerDisplay = callerName.ifEmpty { callerNumber }
            val telecomAddress = Uri.fromParts("tel", callerNumber, null)
            Rlog.w(TAG, "Incoming call notify from=$callerNumber callerName=$callerName callerUri=$callerUri callId=$callId from=$from")

            val callProfile = ImsCallProfile(ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VOICE,
                Bundle(),
                ImsStreamMediaProfile(
                    ImsStreamMediaProfile.AUDIO_QUALITY_EVS_FB,
                    ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE,
                    ImsStreamMediaProfile.VIDEO_QUALITY_NONE,
                    ImsStreamMediaProfile.DIRECTION_INACTIVE,
                    ImsStreamMediaProfile.RTT_MODE_DISABLED,
                ))
            callProfile.setCallExtra(ImsCallProfile.EXTRA_OI, callerNumber)
            callProfile.setCallExtra(ImsCallProfile.EXTRA_CNA, callerDisplay)
            callProfile.setCallExtra(ImsCallProfile.EXTRA_DISPLAY_TEXT, callerDisplay)
            callProfile.setCallExtraInt(ImsCallProfile.EXTRA_OIR, presentation)
            callProfile.setCallExtraInt(ImsCallProfile.EXTRA_CNAP, presentation)

            Rlog.w(TAG, "Incoming call extras: ${extras.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            val incomingSession = object: ImsCallSessionImplBase() {
                var callListener: ImsCallSessionListener? = null
                var mState = State.INITIATED
                override fun getCallProfile(): ImsCallProfile {
                    Rlog.w(TAG, "getCallProfile called EXTRA_OI=${callProfile.getCallExtra(ImsCallProfile.EXTRA_OI)} callId=$callId")
                    return callProfile
                }
                override fun setListener(listener: ImsCallSessionListener) {
                    Rlog.d(TAG, "Setting incoming CallListener to $listener")
                    callListener = listener
                    incomingCallListener = listener
                    listener.callSessionProgressing(callProfile.mediaProfile)
                }

                override fun getCallId(): String {
                    return callId
                }

                override fun isInCall(): Boolean {
                    return mState != State.TERMINATED
                }

                override fun getLocalCallProfile(): ImsCallProfile {
                    return callProfile
                }
                override fun getRemoteCallProfile(): ImsCallProfile {
                    return callProfile
                }
                override fun getProperty(name: String): String {
                    Rlog.d(TAG, "ImsCallSession.getProperty " + name)
                    return callProfile.getCallExtra(name, "")
                }

                override fun getState(): Int {
                    return mState
                }

                override fun start(callee: String, profile: ImsCallProfile) {
                    Rlog.d(TAG, "Starting call with $callee")
                }

                override fun accept(callType: Int, profile: ImsStreamMediaProfile) {
                    Rlog.d(TAG, "Accepting call with profile $profile")
                    sipHandler.acceptCall()
                    mState = State.ESTABLISHED
                    callListener?.callSessionInitiated(callProfile)
                }

                override fun deflect(deflectNumber: String?) {
                    Rlog.d(TAG, "Deflecting call to $deflectNumber")
                }

                override fun reject(reason: Int) {
                    Rlog.w(TAG, "Rejecting call $reason")
                    sipHandler.rejectCall()
                    mState = State.TERMINATED
                    Rlog.w(TAG, "Rejecting call done")
                }

                override fun terminate(reason: Int) {
                    Rlog.w(TAG, "Terminating call reason=$reason")
                    sipHandler.terminateCall()
                    mState = State.TERMINATED
                    callListener?.callSessionTerminated(
                        ImsReasonInfo(
                            ImsReasonInfo.CODE_USER_TERMINATED,
                            0,
                            "Local hangup"
                        )
                    )
                    Rlog.w(TAG, "Terminating call done, mState=$mState")
                }

            }
            val incomingExtras = Bundle().apply {
                putString("call-id", callId)
                putString(ImsCallProfile.EXTRA_OI, callerNumber)
                putString(ImsCallProfile.EXTRA_CNA, callerDisplay)
                putInt(ImsCallProfile.EXTRA_OIR, presentation)
                putInt(ImsCallProfile.EXTRA_CNAP, presentation)
                putString(ImsCallProfile.EXTRA_DISPLAY_TEXT, callerDisplay)
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, telecomAddress)
                putString("android.telecom.extra.CALLER_DISPLAY_NAME", callerDisplay)
                putString("raw-from", extras["raw-from"])
                putString("raw-p-asserted-identity", extras["raw-p-asserted-identity"])
                putString("raw-remote-party-id", extras["raw-remote-party-id"])
            }
            notifyIncomingCall(incomingSession, incomingExtras)
            Rlog.w(TAG, "notifyIncomingCall returned for $callId presentation=$presentation oi=${callProfile.getCallExtra(ImsCallProfile.EXTRA_OI)} cna=${callProfile.getCallExtra(ImsCallProfile.EXTRA_CNA)}")
        }
        sipHandler.onCancelledCall = { param: Object, s: String, map: Map<String, String> ->
            Rlog.w(TAG, "Cancelling call listener=$incomingCallListener outgoing=$outgoingCallListener")
            val l = incomingCallListener ?: outgoingCallListener
            val statusCode = map["statusCode"]?.toInt() ?: -1
            if (statusCode >= 400) {
                val statusMessage = map["statusString"] ?: "Kikoo"
                Rlog.w(TAG, "Remote/network terminated call with $statusCode $statusMessage")
                l?.callSessionTerminated(ImsReasonInfo(ImsReasonInfo.CODE_NETWORK_REJECT, 0, statusMessage))
            } else {
                Rlog.w(TAG, "Remote terminated call")
                l?.callSessionTerminated(
                    ImsReasonInfo(
                        ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE,
                        0,
                        "Kikoo"
                    )
                )
            }
        }

        imsService.getRegistration(slotId).onRegistering(REGISTRATION_TECH_LTE)
        sipHandler.getVolteNetwork()
    }

    override fun onFeatureRemoved() {
        Rlog.d(TAG, "$slotId onFeatureRemoved")
    }

    // ints are @MmTelCapabilities.MmTelCapability and @ImsRegistrationImplBase.ImsRegistrationTech
    override fun queryCapabilityConfiguration(capability: Int, radioTech: Int): Boolean {
        Rlog.d(TAG, "$slotId queryCapabilityConfiguration $capability $radioTech")
        return capability == MmTelCapabilities.CAPABILITY_TYPE_SMS || capability == MmTelCapabilities.CAPABILITY_TYPE_VOICE
    }

    override fun setUiTtyMode(mode: Int, onCompleteMessage: Message?) {
        Rlog.d(TAG, "$slotId setUiTtyMode $onCompleteMessage")
    }

    override fun shouldProcessCall(numbers: Array<out String>): Int {
        Rlog.d(TAG, "Should process call? ${numbers.toList()}")
        return PROCESS_CALL_IMS
    }
}
