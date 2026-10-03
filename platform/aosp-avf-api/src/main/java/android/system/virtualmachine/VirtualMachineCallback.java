// Compile-only AOSP API declarations. Never package these classes in an APK.
// Source ABI: packages/modules/Virtualization @ 175a51b30123fa6b02b541f1969665708f7ec2c3.
package android.system.virtualmachine;

public interface VirtualMachineCallback {
    void onPayloadStarted(VirtualMachine vm);
    void onPayloadReady(VirtualMachine vm);
    void onPayloadFinished(VirtualMachine vm, int exitCode);
    void onError(VirtualMachine vm, int errorCode, String message);
    void onStopped(VirtualMachine vm, int reason);
}
