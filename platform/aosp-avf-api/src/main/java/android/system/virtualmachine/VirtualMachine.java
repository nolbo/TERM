// Compile-only AOSP API declarations. Never package these classes in an APK.
// Source ABI: packages/modules/Virtualization @ 175a51b30123fa6b02b541f1969665708f7ec2c3.
package android.system.virtualmachine;

public class VirtualMachine {
    public static final int STATUS_STOPPED = 0;
    public int getStatus() { throw new UnsupportedOperationException("Compile-only API"); }
    public VirtualMachineConfig setConfig(VirtualMachineConfig config) { throw new UnsupportedOperationException("Compile-only API"); }
    public void setCallback(java.util.concurrent.Executor executor, VirtualMachineCallback callback) { throw new UnsupportedOperationException("Compile-only API"); }
    public void run() { throw new UnsupportedOperationException("Compile-only API"); }
    public void stop() { throw new UnsupportedOperationException("Compile-only API"); }
    public android.os.ParcelFileDescriptor connectVsock(long port) { throw new UnsupportedOperationException("Compile-only API"); }
}
