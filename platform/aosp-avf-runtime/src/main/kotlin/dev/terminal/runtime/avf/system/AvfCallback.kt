package dev.terminal.runtime.avf.system

import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCallback
import dev.terminal.runtime.avf.AvfPlatformEvent

internal fun avfCallback(boot: AvfGuestBoot, isCurrent: () -> Boolean, instanceId: String,
    markMachineStopped: (VirtualMachine, String) -> Unit, emit: (AvfPlatformEvent) -> Boolean): VirtualMachineCallback {
        return object : VirtualMachineCallback {
            override fun onPayloadStarted(vm: VirtualMachine) {
                if (isCurrent()) dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.payload.started", instanceId)
            }

            override fun onPayloadReady(vm: VirtualMachine) {
                if (isCurrent()) dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.payload.ready", instanceId)
            }

            override fun onPayloadFinished(vm: VirtualMachine, exitCode: Int) {
                if (!isCurrent()) return
                dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.payload.finished", instanceId, exitCode.toString())
                markMachineStopped(vm, instanceId)
                if (exitCode == 0) {
                    boot.fail(IllegalStateException("Guest exited during boot"))
                    emit(AvfPlatformEvent.Stopped)
                } else {
                    val failure = IllegalStateException("Guest exited (code $exitCode)")
                    boot.fail(failure)
                    emit(AvfPlatformEvent.Failed(failure.message.orEmpty()))
                }
            }

            override fun onError(vm: VirtualMachine, errorCode: Int, message: String) {
                if (!isCurrent()) return
                dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.error", instanceId, errorCode.toString())
                markMachineStopped(vm, instanceId)
                val failure = IllegalStateException("AVF error $errorCode: $message")
                boot.fail(failure)
                emit(AvfPlatformEvent.Failed(failure.message.orEmpty()))
            }

            override fun onStopped(vm: VirtualMachine, reason: Int) {
                if (!isCurrent()) return
                dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.stopped", instanceId, reason.toString())
                markMachineStopped(vm, instanceId)
                boot.fail(IllegalStateException("AVF stopped (reason $reason)"))
                emit(AvfPlatformEvent.Stopped)
            }
        }
    }
