//! Lumora's native library: one `.so` for the whole app, one coarse JNI call per job.
//! See the "Native code (Rust) policy" section of CLAUDE.md.

mod xmltv;

use jni::objects::{JByteArray, JClass, JObject, JValue};
use jni::sys::jbyteArray;
use jni::JNIEnv;
use std::io::{self, BufReader, Read};

/// One `InputStream.read(byte[], 0, n)` per chunk: 64 KB keeps the JNI round trips at a few
/// hundred for the largest feeds, and the buffer small enough to stay out of the large-object
/// space on a 1 GB device.
const CHUNK: usize = 64 * 1024;

/// A `java.io.InputStream` read from Rust. A Java exception raised by `read` (a dropped
/// connection, typically) is left pending on the thread and surfaces as an `io::Error` here,
/// so the parse stops and the caller sees the original exception.
struct StreamReader<'a> {
    env: JNIEnv<'a>,
    stream: JObject<'a>,
    buf: JByteArray<'a>,
}

impl Read for StreamReader<'_> {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        let want = out.len().min(CHUNK) as i32;
        let n = self
            .env
            .call_method(&self.stream, "read", "([BII)I", &[JValue::Object(&self.buf), JValue::Int(0), JValue::Int(want)])
            .and_then(|v| v.i())
            .map_err(|e| io::Error::new(io::ErrorKind::Other, e.to_string()))?;
        if n <= 0 {
            return Ok(0);
        }
        let n = n as usize;
        // jbyte is i8; the bytes are the same, only the view differs.
        let dst = unsafe { std::slice::from_raw_parts_mut(out.as_mut_ptr() as *mut i8, n) };
        self.env
            .get_byte_array_region(&self.buf, 0, dst)
            .map_err(|e| io::Error::new(io::ErrorKind::Other, e.to_string()))?;
        Ok(n)
    }
}

/// `LumoraNative.parseXmltv(InputStream): ByteArray` - the record format is documented in
/// `xmltv.rs`. Throws `java.io.IOException` for a malformed or cut-off feed, the same
/// failure the Kotlin parser reports; never panics (the release profile aborts on panic,
/// which would take the app down).
#[no_mangle]
pub extern "system" fn Java_com_lumora_nativelib_LumoraNative_parseXmltv<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    stream: JObject<'a>,
) -> jbyteArray {
    let buf = match env.new_byte_array(CHUNK as i32) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(), // OutOfMemoryError already pending
    };
    let mut reader = StreamReader { env, stream, buf };
    let result = xmltv::parse(BufReader::with_capacity(CHUNK, &mut reader));
    let mut env = reader.env;
    match result {
        Ok(blob) => match env.byte_array_from_slice(&blob) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(msg) => {
            // A pending Java exception (from the stream) is the more useful one: keep it.
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/io/IOException", msg);
            }
            std::ptr::null_mut()
        }
    }
}
