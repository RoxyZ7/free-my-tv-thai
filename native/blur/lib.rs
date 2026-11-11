#![no_std]

use core::ffi::c_void;
use core::{mem, ptr, slice};

type Env = *mut *const *const c_void;
type Object = *mut c_void;
type Length = unsafe extern "system" fn(Env, Object) -> i32;
type Pin = unsafe extern "system" fn(Env, Object, *mut u8) -> *mut u32;
type Unpin = unsafe extern "system" fn(Env, Object, *mut u32, i32);

const GET_ARRAY_LENGTH: usize = 171;
const GET_PRIMITIVE_ARRAY_CRITICAL: usize = 222;
const RELEASE_PRIMITIVE_ARRAY_CRITICAL: usize = 223;
const JNI_ABORT: i32 = 2;

#[panic_handler]
fn panic(_: &core::panic::PanicInfo<'_>) -> ! {
    // A panic cannot unwind through JNI.
    #[cfg(target_arch = "aarch64")]
    unsafe {
        core::arch::asm!("brk #0", options(noreturn))
    }
    #[cfg(target_arch = "arm")]
    unsafe {
        core::arch::asm!("udf #0", options(noreturn))
    }
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    unsafe {
        core::arch::asm!("ud2", options(noreturn))
    }
}

// The precompiled core references this symbol in unwind metadata. This library always aborts.
#[unsafe(no_mangle)]
extern "C" fn rust_eh_personality() -> ! {
    panic!("unwinding a panic through JNI is unsupported")
}

#[inline]
fn box_filter<const R: usize>(
    input: &[u32],
    output: &mut [u32],
    length: usize,
    lines: usize,
    step: usize,
    line_step: usize,
) {
    let diameter = (2 * R + 1) as u32;
    let last = length - 1;
    for line in 0..lines {
        let base = line * line_step;
        let (mut red, mut green, mut blue) = (0, 0, 0);
        for i in 0..=2 * R {
            let p = input[base + i.saturating_sub(R).min(last) * step];
            red += (p >> 16) & 255;
            green += (p >> 8) & 255;
            blue += p & 255;
        }
        for i in 0..length {
            output[base + i * step] = 0xff00_0000
                | ((red + R as u32) / diameter) << 16
                | ((green + R as u32) / diameter) << 8
                | (blue + R as u32) / diameter;
            let add = input[base + (i + R + 1).min(last) * step];
            let sub = input[base + i.saturating_sub(R) * step];
            red = red + ((add >> 16) & 255) - ((sub >> 16) & 255);
            green = green + ((add >> 8) & 255) - ((sub >> 8) & 255);
            blue = blue + (add & 255) - (sub & 255);
        }
    }
}

fn blur<const R: usize>(pixels: &mut [u32], scratch: &mut [u32], w: usize, h: usize, passes: i32) {
    for _ in 0..passes {
        box_filter::<R>(pixels, scratch, w, h, 1, w);
        box_filter::<R>(scratch, pixels, h, w, w, 1);
    }
}

// Java owns the arrays and does not share them with other threads.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_freemytv_RustBlur_blurNative(
    env: Env,
    _: Object,
    pixels: Object,
    scratch: Object,
    w: i32,
    h: i32,
    radius: i32,
    passes: i32,
) -> u8 {
    if pixels.is_null()
        || scratch.is_null()
        || pixels == scratch
        || w <= 0
        || h <= 0
        || !(radius == 2 || radius == 3)
        || !(1..=3).contains(&passes)
    {
        return 0;
    }
    let Some(size) = w.checked_mul(h) else {
        return 0;
    };
    // Keep the JNI critical region short. Larger images can use the managed path.
    if size > 160_000 {
        return 0;
    }
    unsafe {
        let length: Length = mem::transmute(*(*env).add(GET_ARRAY_LENGTH));
        let pin: Pin = mem::transmute(*(*env).add(GET_PRIMITIVE_ARRAY_CRITICAL));
        let unpin: Unpin = mem::transmute(*(*env).add(RELEASE_PRIMITIVE_ARRAY_CRITICAL));
        if length(env, pixels) < size || length(env, scratch) < size {
            return 0;
        }
        let input = pin(env, pixels, ptr::null_mut());
        if input.is_null() {
            return 0;
        }
        let work = pin(env, scratch, ptr::null_mut());
        if work.is_null() {
            unpin(env, pixels, input, JNI_ABORT);
            return 0;
        }
        let p = slice::from_raw_parts_mut(input, size as usize);
        let t = slice::from_raw_parts_mut(work, size as usize);
        if radius == 2 {
            blur::<2>(p, t, w as usize, h as usize, passes);
        } else {
            blur::<3>(p, t, w as usize, h as usize, passes);
        }
        unpin(env, scratch, work, JNI_ABORT);
        unpin(env, pixels, input, 0);
        1
    }
}

#[inline]
fn round_positive(value: f32) -> u32 {
    let whole = value as u32;
    whole + u32::from(value - whole as f32 >= 0.5)
}

fn tint_pixels(
    source: &mut [u32],
    light: &mut [u32],
    mut dark: Option<&mut [u32]>,
    light_amount: f32,
    dark_amount: f32,
) {
    let light_keep = 1.0 - light_amount;
    let light_base = 255.0 * light_amount;
    let dark_keep = 1.0 - dark_amount;
    let dark_base = 11.0 * dark_amount;
    let dark_blue = 2.0 * dark_amount;
    // A channel has 256 values. Compute its color mapping once per image.
    let mut table = [mem::MaybeUninit::<(u32, u32, u32)>::uninit(); 256];
    for (i, item) in table.iter_mut().enumerate() {
        let value = i as f32;
        let night = value * dark_keep + dark_base;
        item.write((
            round_positive(value * light_keep + light_base),
            round_positive(night),
            round_positive(night + dark_blue),
        ));
    }
    // Every table slot was written above before any lookup.
    let lookup = |channel: u32| unsafe { *table[(channel & 255) as usize].assume_init_ref() };
    for (i, (pixel, day)) in source.iter_mut().zip(light.iter_mut()).enumerate() {
        let (r_day, r_night, _) = lookup(*pixel >> 16);
        let (g_day, g_night, _) = lookup(*pixel >> 8);
        let (b_day, _, b_night) = lookup(*pixel);
        *day = 0xff00_0000 | (r_day << 16) | (g_day << 8) | b_day;
        let night = 0xff00_0000 | (r_night << 16) | (g_night << 8) | b_night;
        if let Some(target) = dark.as_deref_mut() {
            target[i] = night;
        } else {
            *pixel = night;
        }
    }
}

// A null dark array means the Java caller owns the source and wants to reuse it.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_freemytv_RustBlur_tintNative(
    env: Env,
    _: Object,
    source: Object,
    light: Object,
    dark: Object,
    light_amount: f32,
    dark_amount: f32,
) -> u8 {
    if source.is_null()
        || light.is_null()
        || source == light
        || (!dark.is_null() && (source == dark || light == dark))
        || !(0.0..=1.0).contains(&light_amount)
        || !(0.0..=1.0).contains(&dark_amount)
    {
        return 0;
    }
    unsafe {
        let length: Length = mem::transmute(*(*env).add(GET_ARRAY_LENGTH));
        let pin: Pin = mem::transmute(*(*env).add(GET_PRIMITIVE_ARRAY_CRITICAL));
        let unpin: Unpin = mem::transmute(*(*env).add(RELEASE_PRIMITIVE_ARRAY_CRITICAL));
        let size = length(env, source);
        if size <= 0
            || size > 160_000
            || length(env, light) < size
            || (!dark.is_null() && length(env, dark) < size)
        {
            return 0;
        }
        let source_ptr = pin(env, source, ptr::null_mut());
        if source_ptr.is_null() {
            return 0;
        }
        let light_ptr = pin(env, light, ptr::null_mut());
        if light_ptr.is_null() {
            unpin(env, source, source_ptr, JNI_ABORT);
            return 0;
        }
        let dark_ptr = if dark.is_null() {
            ptr::null_mut()
        } else {
            pin(env, dark, ptr::null_mut())
        };
        if !dark.is_null() && dark_ptr.is_null() {
            unpin(env, light, light_ptr, JNI_ABORT);
            unpin(env, source, source_ptr, JNI_ABORT);
            return 0;
        }
        let pixels = slice::from_raw_parts_mut(source_ptr, size as usize);
        let day = slice::from_raw_parts_mut(light_ptr, size as usize);
        let night = if dark_ptr.is_null() {
            None
        } else {
            Some(slice::from_raw_parts_mut(dark_ptr, size as usize))
        };
        tint_pixels(pixels, day, night, light_amount, dark_amount);
        if !dark_ptr.is_null() {
            unpin(env, dark, dark_ptr, 0);
        }
        unpin(env, light, light_ptr, 0);
        unpin(
            env,
            source,
            source_ptr,
            if dark.is_null() { 0 } else { JNI_ABORT },
        );
        1
    }
}
