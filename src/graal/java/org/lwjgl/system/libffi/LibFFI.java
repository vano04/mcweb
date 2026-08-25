package org.lwjgl.system.libffi;

import java.nio.ByteBuffer;
import org.lwjgl.PointerBuffer;

/**
 * Pure-Java stand-in for LWJGL's libffi bindings. Web Image cannot link the
 * real native methods; Minecraft still loads GLFW callback interfaces whose
 * static initializers touch these constants. Function addresses remain zero:
 * browser input will use DOM events rather than native closures.
 */
public class LibFFI {
    public static final String FFI_VERSION_STRING = "browser-0";
    public static final int FFI_VERSION_NUMBER = 0;

    public static final short FFI_TYPE_VOID = 0;
    public static final short FFI_TYPE_INT = 1;
    public static final short FFI_TYPE_FLOAT = 2;
    public static final short FFI_TYPE_DOUBLE = 3;
    public static final short FFI_TYPE_LONGDOUBLE = 4;
    public static final short FFI_TYPE_UINT8 = 5;
    public static final short FFI_TYPE_SINT8 = 6;
    public static final short FFI_TYPE_UINT16 = 7;
    public static final short FFI_TYPE_SINT16 = 8;
    public static final short FFI_TYPE_UINT32 = 9;
    public static final short FFI_TYPE_SINT32 = 10;
    public static final short FFI_TYPE_UINT64 = 11;
    public static final short FFI_TYPE_SINT64 = 12;
    public static final short FFI_TYPE_STRUCT = 13;
    public static final short FFI_TYPE_POINTER = 14;

    public static final int FFI_FIRST_ABI = 1;
    public static final int FFI_WIN64 = 1;
    public static final int FFI_GNUW64 = 2;
    public static final int FFI_UNIX64 = 2;
    public static final int FFI_EFI64 = 3;
    public static final int FFI_SYSV = 1;
    public static final int FFI_STDCALL = 2;
    public static final int FFI_THISCALL = 3;
    public static final int FFI_FASTCALL = 4;
    public static final int FFI_MS_CDECL = 5;
    public static final int FFI_PASCAL = 6;
    public static final int FFI_REGISTER = 7;
    public static final int FFI_VFP = 8;
    public static final int FFI_LAST_ABI = 8;
    public static final int FFI_DEFAULT_ABI = FFI_SYSV;

    public static final int FFI_OK = 0;
    public static final int FFI_BAD_TYPEDEF = 1;
    public static final int FFI_BAD_ABI = 2;
    public static final int FFI_BAD_ARGTYPE = 3;

    // Opaque stand-in addresses; never dereferenced by browser seams.
    public static final FFIType ffi_type_void = type(0x1001L);
    public static final FFIType ffi_type_uint8 = type(0x1002L);
    public static final FFIType ffi_type_sint8 = type(0x1003L);
    public static final FFIType ffi_type_uint16 = type(0x1004L);
    public static final FFIType ffi_type_sint16 = type(0x1005L);
    public static final FFIType ffi_type_uint32 = type(0x1006L);
    public static final FFIType ffi_type_sint32 = type(0x1007L);
    public static final FFIType ffi_type_uint64 = type(0x1008L);
    public static final FFIType ffi_type_sint64 = type(0x1009L);
    public static final FFIType ffi_type_uchar = type(0x100AL);
    public static final FFIType ffi_type_schar = type(0x100BL);
    public static final FFIType ffi_type_ushort = type(0x100CL);
    public static final FFIType ffi_type_sshort = type(0x100DL);
    public static final FFIType ffi_type_uint = type(0x100EL);
    public static final FFIType ffi_type_sint = type(0x100FL);
    public static final FFIType ffi_type_ulong = type(0x1010L);
    public static final FFIType ffi_type_slong = type(0x1011L);
    public static final FFIType ffi_type_float = type(0x1012L);
    public static final FFIType ffi_type_double = type(0x1013L);
    public static final FFIType ffi_type_longdouble = type(0x1014L);
    public static final FFIType ffi_type_pointer = type(0x1015L);

    private LibFFI() {
    }

    private static FFIType type(long address) {
        return FFIType.create(address);
    }

    public static long nffi_get_version() {
        return 0L;
    }

    public static String ffi_get_version() {
        return FFI_VERSION_STRING;
    }

    public static long ffi_get_version_number() {
        return FFI_VERSION_NUMBER;
    }

    public static int ffi_get_default_abi() {
        return FFI_DEFAULT_ABI;
    }

    public static int nffi_prep_cif(long cif, int abi, int nargs, long rtype, long atypes) {
        return FFI_OK;
    }

    public static int ffi_prep_cif(FFICIF cif, int abi, FFIType rtype, PointerBuffer atypes) {
        return FFI_OK;
    }

    public static int nffi_prep_cif_var(
            long cif,
            int abi,
            int nfixedargs,
            int ntotalargs,
            long rtype,
            long atypes
    ) {
        return FFI_OK;
    }

    public static int ffi_prep_cif_var(
            FFICIF cif,
            int abi,
            int nfixedargs,
            FFIType rtype,
            PointerBuffer atypes
    ) {
        return FFI_OK;
    }

    public static void nffi_call(long cif, long fn, long rvalue, long avalue) {
    }

    public static void ffi_call(FFICIF cif, long fn, ByteBuffer rvalue, PointerBuffer avalue) {
    }

    public static int nffi_get_struct_offsets(int abi, long structType, long offsets) {
        return FFI_OK;
    }

    public static int ffi_get_struct_offsets(int abi, FFIType structType, PointerBuffer offsets) {
        return FFI_OK;
    }

    public static long ffi_get_closure_size() {
        return 64L;
    }

    public static long nffi_closure_alloc(long size, long code) {
        return 0L;
    }

    public static FFIClosure ffi_closure_alloc(long size, PointerBuffer code) {
        return null;
    }

    public static void nffi_closure_free(long writable) {
    }

    public static void ffi_closure_free(FFIClosure writable) {
    }

    public static int nffi_prep_closure_loc(
            long closure,
            long cif,
            long fun,
            long userData,
            long codeloc
    ) {
        return FFI_OK;
    }

    public static int ffi_prep_closure_loc(
            FFIClosure closure,
            FFICIF cif,
            long fun,
            long userData,
            long codeloc
    ) {
        return FFI_OK;
    }
}
