// Compile-only AOSP API declarations. Never package these classes in an APK.
// Source ABI: packages/modules/Virtualization @ 175a51b30123fa6b02b541f1969665708f7ec2c3.
package android.system.virtualmachine;

public class VirtualMachineManager {
    public VirtualMachine getOrCreate(String name, VirtualMachineConfig config) { throw new UnsupportedOperationException("Compile-only API"); }
    public VirtualMachine get(String name) { throw new UnsupportedOperationException("Compile-only API"); }
    public void delete(String name) { throw new UnsupportedOperationException("Compile-only API"); }
}
