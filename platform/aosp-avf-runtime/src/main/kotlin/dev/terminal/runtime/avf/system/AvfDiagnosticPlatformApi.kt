package dev.terminal.runtime.avf.system

import android.content.Context
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCallback
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.system.virtualmachine.VirtualMachineManager
import java.util.concurrent.Executor

/** Keep optional system classes behind the same capability checks as backend creation. */
internal fun avfCustomImageBuilder() = VirtualMachineCustomImageConfig.Builder()
internal fun avfVirtualMachineManager(context: Context): VirtualMachineManager? =
    context.applicationContext.getSystemService(VirtualMachineManager::class.java)

internal fun checkAvfCustomImageApi() {
    avfCustomImageBuilder() // Same side-effect-free access probe as AospAvfRuntime.
    val builder = VirtualMachineCustomImageConfig.Builder::class.java
    for (name in listOf("setName", "setKernelPath", "setInitrdPath", "addParam"))
        method(builder, name, builder, String::class.java)
    for (name in listOf("useNetwork", "useAutoMemoryBalloon")) method(builder, name, builder, Boolean::class.javaPrimitiveType!!)
    method(builder, "addDisk", builder, VirtualMachineCustomImageConfig.Disk::class.java)
    method(builder, "build", VirtualMachineCustomImageConfig::class.java)
    for (name in listOf("RODisk", "RWDisk")) method(VirtualMachineCustomImageConfig.Disk::class.java,
        name, VirtualMachineCustomImageConfig.Disk::class.java, String::class.java)
    val config = VirtualMachineConfig.Builder::class.java
    config.getConstructor(Context::class.java)
    method(config, "setCustomImageConfig", config, VirtualMachineCustomImageConfig::class.java)
}

internal fun checkAvfDriverAbi() {
    // Inspect signatures without invoking get/getOrCreate/delete/run/stop or building a VM config.
    val manager = VirtualMachineManager::class.java
    method(manager, "get", VirtualMachine::class.java, String::class.java)
    method(manager, "getOrCreate", VirtualMachine::class.java, String::class.java, VirtualMachineConfig::class.java)
    method(manager, "delete", Void.TYPE, String::class.java)
    val machine = VirtualMachine::class.java
    for (name in listOf("run", "stop")) method(machine, name, Void.TYPE)
    method(machine, "getStatus", Int::class.javaPrimitiveType!!)
    method(machine, "setConfig", VirtualMachineConfig::class.java, VirtualMachineConfig::class.java)
    method(machine, "setCallback", Void.TYPE, Executor::class.java, VirtualMachineCallback::class.java)
    method(machine, "connectVsock", android.os.ParcelFileDescriptor::class.java, Long::class.javaPrimitiveType!!)
    val config = VirtualMachineConfig.Builder::class.java
    for (name in listOf("setProtectedVm", "setVmOutputCaptured", "setConnectVmConsole"))
        method(config, name, config, Boolean::class.javaPrimitiveType!!)
    method(config, "setMemoryBytes", config, Long::class.javaPrimitiveType!!)
    for (name in listOf("setCpuTopology", "setDebugLevel")) method(config, name, config, Int::class.javaPrimitiveType!!)
    method(config, "build", VirtualMachineConfig::class.java)
}

private fun method(owner: Class<*>, name: String, result: Class<*>, vararg parameters: Class<*>) {
    val actual = owner.getMethod(name, *parameters)
    if (actual.returnType != result) throw NoSuchMethodException("${owner.name}.$name: incompatible return type")
}
