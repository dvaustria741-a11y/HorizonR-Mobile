//! Input from the Kotlin touch-controls overlay (`dev.horizon.android.controls.HorizonBridge`).
//!
//! The overlay lives in its own Android window above the game surface and cannot call into the
//! game thread, so its calls only queue here; `Game::poll_touch_overlay` applies them once a
//! frame. Commands reuse the control socket's line interpreter, pointer input is replayed as the
//! window events a mouse would send, so every screen treats it as ordinary mouse and keyboard.
use super::*;
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jboolean, jfloat, jint},
};
use std::collections::VecDeque;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

enum Input {
    Command(String),
    Look(f32, f32),
    Wheel(f32),
    Cursor(f32, f32),
    Button(i32, bool),
    /// A finger the overlay did not use for a button or stick, handed to the engine as a native touch.
    /// `phase`: 0 started, 1 moved, 2 ended, anything else cancelled.
    Touch {
        pointer: u64,
        phase: i32,
        x: f32,
        y: f32,
    },
}

const TOUCH_MOVED: i32 = 1;

/// More than a few frames of unread pointer motion is stale; presses and releases are never dropped.
const QUEUE_LIMIT: usize = 256;

static QUEUE: Mutex<VecDeque<Input>> = Mutex::new(VecDeque::new());

struct AtomicF32(AtomicU32);
impl AtomicF32 {
    const fn new() -> Self {
        Self(AtomicU32::new(0))
    }
    fn get(&self) -> f32 {
        f32::from_bits(self.0.load(Ordering::Relaxed))
    }
    fn set(&self, value: f32) {
        self.0.store(value.to_bits(), Ordering::Relaxed);
    }
}

/// The overlay view's size in pixels, as it last reported it (`nativeSetScale`).
static VIEW_W: AtomicF32 = AtomicF32::new();
static VIEW_H: AtomicF32 = AtomicF32::new();
/// Where the game's window sits inside the overlay view, in the overlay's pixels (`nativeSetArea`).
/// The overlay covers the whole display, display cutout included, but the game's window may not, so
/// a point of the overlay is only the same point of the game once this offset is taken off.
static AREA_X: AtomicF32 = AtomicF32::new();
static AREA_Y: AtomicF32 = AtomicF32::new();
static AREA_W: AtomicF32 = AtomicF32::new();
static AREA_H: AtomicF32 = AtomicF32::new();
/// The game surface's size, and the GUI's scale and size in GUI pixels (what the HUD is laid out
/// in), published by the game thread every frame.
static SURFACE_W: AtomicF32 = AtomicF32::new();
static SURFACE_H: AtomicF32 = AtomicF32::new();
static HUD_SCALE: AtomicF32 = AtomicF32::new();
static HUD_W: AtomicF32 = AtomicF32::new();
static HUD_H: AtomicF32 = AtomicF32::new();
/// The mouse is grabbed for play (the overlay then sends look motion, not a cursor).
static CAPTURED: AtomicBool = AtomicBool::new(false);
/// The overlay is showing (`nativeSetActive`).
static ACTIVE: AtomicBool = AtomicBool::new(false);

fn push(input: Input) {
    let mut queue = QUEUE.lock().unwrap_or_else(|e| e.into_inner());
    match (queue.back_mut(), &input) {
        (Some(Input::Look(x, y)), Input::Look(dx, dy)) => {
            *x += dx;
            *y += dy;
            return;
        }
        (Some(Input::Wheel(lines)), Input::Wheel(more)) => {
            *lines += more;
            return;
        }
        (Some(Input::Cursor(x, y)), Input::Cursor(nx, ny)) => {
            *x = *nx;
            *y = *ny;
            return;
        }
        (
            Some(Input::Touch {
                pointer,
                phase: TOUCH_MOVED,
                x,
                y,
            }),
            Input::Touch {
                pointer: next,
                phase: TOUCH_MOVED,
                x: nx,
                y: ny,
            },
        ) if pointer == next => {
            *x = *nx;
            *y = *ny;
            return;
        }
        _ => {}
    }
    let motion = matches!(
        input,
        Input::Look(..)
            | Input::Wheel(_)
            | Input::Cursor(..)
            | Input::Touch {
                phase: TOUCH_MOVED,
                ..
            }
    );
    if motion && queue.len() >= QUEUE_LIMIT {
        return;
    }
    queue.push_back(input);
}

fn drain() -> Vec<Input> {
    QUEUE
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .drain(..)
        .collect()
}

/// A point in the overlay view's pixels, in the game surface's.
fn surface_point(x: f32, y: f32) -> (f64, f64) {
    let (sw, sh) = (SURFACE_W.get(), SURFACE_H.get());
    let (aw, ah) = (AREA_W.get(), AREA_H.get());
    if aw > 0.0 && ah > 0.0 && sw > 0.0 && sh > 0.0 {
        // The game window's rectangle in the overlay, scaled to the surface (which can be rendered smaller).
        let px = ((x - AREA_X.get()) / aw * sw).clamp(0.0, sw);
        let py = ((y - AREA_Y.get()) / ah * sh).clamp(0.0, sh);
        return (f64::from(px), f64::from(py));
    }
    let (vw, vh) = (VIEW_W.get(), VIEW_H.get());
    if vw <= 0.0 || vh <= 0.0 || sw <= 0.0 || sh <= 0.0 {
        return (f64::from(x), f64::from(y));
    }
    (f64::from(x / vw * sw), f64::from(y / vh * sh))
}

/// The hotbar slot (0..9) under a point of the overlay view, or -1. The bar is laid out as
/// `hud_layout::hotbar_with_attack_side` draws it: 182x22 GUI pixels, centred on the integer half
/// of the GUI width and sitting on the bottom of the GUI height (both rounded up from the surface).
fn hotbar_slot(x: f32, y: f32) -> i32 {
    let gui = HUD_SCALE.get();
    let (gw, gh) = (HUD_W.get() as i32, HUD_H.get() as i32);
    if gui <= 0.0 || gw <= 0 || gh <= 0 {
        return -1;
    }
    let (px, py) = surface_point(x, y);
    let (gx, gy) = (px as f32 / gui, py as f32 / gui);
    if gy < (gh - 22) as f32 || gy > gh as f32 {
        return -1;
    }
    let along = gx - (gw / 2 - 91) as f32;
    if !(1.0..181.0).contains(&along) {
        return -1;
    }
    (((along - 1.0) / 20.0) as i32).clamp(0, 8)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_controls_HorizonBridge_nativeCommand(
    mut env: JNIEnv,
    _class: JClass,
    line: JString,
) {
    if let Ok(line) = env.get_string(&line) {
        push(Input::Command(line.into()));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_controls_HorizonBridge_nativeLook(
    _env: JNIEnv,
    _class: JClass,
    dx: jfloat,
    dy: jfloat,
) {
    push(Input::Look(dx, dy));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_controls_HorizonBridge_nativeWheel(
    _env: JNIEnv,
    _class: JClass,
    lines: jfloat,
) {
    push(Input::Wheel(lines));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_controls_HorizonBridge_nativeCursor(
    _env: JNIEnv,
    _class: JClass,
    x: jfloat,
    y: jfloat,
) {
    push(Input::Cursor(x, y));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_controls_HorizonBridge_nativeButton(
    _env: JNIEnv,
    _class: JClass,
    button: jint,
    down: jboolean,
) {
    push(Input::Button(button, down != 0));
}

/// A touch the overlay did not claim (menus without the virtual mouse): the engine handles it as
/// it would a touch on its own window, so menu taps, drags and sliders keep working beneath the buttons.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_controls_HorizonBridge_nativeTouch(
    _env: JNIEnv,
    _class: JClass,
    pointer: jint,
    phase: jint,
    x: jfloat,
    y: jfloat,
) {
    push(Input::Touch {
        pointer: u64::from(pointer.max(0) as u32),
        phase,
        x,
        y,
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_controls_HorizonBridge_nativeSetActive(
    _env: JNIEnv,
    _class: JClass,
    on: jboolean,
) {
    ACTIVE.store(on != 0, Ordering::Relaxed);
}

/// The overlay view's width and height in pixels.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_controls_HorizonBridge_nativeSetScale(
    _env: JNIEnv,
    _class: JClass,
    width: jfloat,
    height: jfloat,
) {
    VIEW_W.set(width);
    VIEW_H.set(height);
}

/// The game window's rectangle inside the overlay view, in the overlay's pixels.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_controls_HorizonBridge_nativeSetArea(
    _env: JNIEnv,
    _class: JClass,
    x: jfloat,
    y: jfloat,
    width: jfloat,
    height: jfloat,
) {
    AREA_X.set(x);
    AREA_Y.set(y);
    AREA_W.set(width);
    AREA_H.set(height);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_controls_HorizonBridge_nativeHotbarSlot(
    _env: JNIEnv,
    _class: JClass,
    x: jfloat,
    y: jfloat,
) -> jint {
    hotbar_slot(x, y)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_controls_HorizonBridge_nativeIsCaptured(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    jboolean::from(CAPTURED.load(Ordering::Relaxed))
}

impl Game<'_> {
    /// Applies what the overlay queued since the last frame and publishes what it needs to know.
    pub(super) fn poll_touch_overlay(&mut self, events: &dyn EventLoopCtl) {
        if let Some(renderer) = &self.renderer {
            let size = [renderer.config.width, renderer.config.height];
            SURFACE_W.set(size[0] as f32);
            SURFACE_H.set(size[1] as f32);
            let hud = horizon_client::hud::HudBatch::new(size);
            HUD_SCALE.set(hud.scale);
            HUD_W.set(hud.width);
            HUD_H.set(hud.height);
        }
        CAPTURED.store(
            self.captured && self.screen == Screen::Playing,
            Ordering::Relaxed,
        );
        // The overlay is mouse and keyboard input: while it shows, the input device is Keyboard &
        // mouse whatever mode was saved or picked in-game (configure_input restarts the active mode
        // too, note_input keeps it there once the mode is already Keyboard & mouse).
        if ACTIVE.load(Ordering::Relaxed) {
            use horizon_client::android_gamepad::InputMode;
            if horizon_client::android_runtime::input_mode() != InputMode::KeyboardMouse {
                horizon_client::android_runtime::configure_input(InputMode::KeyboardMouse);
            }
            horizon_client::android_runtime::note_input(InputMode::KeyboardMouse);
        }
        let queued = drain();
        if queued.is_empty() {
            return;
        }
        let Some(id) = self.window.as_ref().map(|w| w.id()) else {
            return;
        };
        for input in queued {
            match input {
                Input::Command(line) => {
                    self.replay_line(&line, events);
                }
                Input::Look(dx, dy) => {
                    // As `device_event` applies a grabbed mouse's motion.
                    if self.pending_save.is_none()
                        && self.captured
                        && self.focused
                        && self.screen == Screen::Playing
                    {
                        self.mouse
                            .on_move(0.0, 0.0, f64::from(dx), f64::from(dy), true, true);
                        self.last_input = std::time::Instant::now();
                    }
                }
                Input::Wheel(lines) => {
                    self.handle_window_event(
                        events,
                        id,
                        WindowEvent::MouseWheel {
                            device_id: DeviceId::dummy(),
                            delta: winit::event::MouseScrollDelta::LineDelta(0.0, lines),
                            phase: winit::event::TouchPhase::Moved,
                        },
                        input_trace::InputOrigin::User,
                    );
                }
                Input::Cursor(x, y) => {
                    let (x, y) = surface_point(x, y);
                    self.handle_window_event(
                        events,
                        id,
                        WindowEvent::CursorMoved {
                            device_id: DeviceId::dummy(),
                            position: winit::dpi::PhysicalPosition::new(x, y),
                        },
                        input_trace::InputOrigin::User,
                    );
                }
                Input::Touch {
                    pointer,
                    phase,
                    x,
                    y,
                } => {
                    use winit::event::TouchPhase;
                    let (x, y) = surface_point(x, y);
                    let phase = match phase {
                        0 => TouchPhase::Started,
                        TOUCH_MOVED => TouchPhase::Moved,
                        2 => TouchPhase::Ended,
                        _ => TouchPhase::Cancelled,
                    };
                    self.handle_window_event(
                        events,
                        id,
                        WindowEvent::Touch(winit::event::Touch {
                            device_id: DeviceId::dummy(),
                            phase,
                            location: winit::dpi::PhysicalPosition::new(x, y),
                            force: None,
                            id: pointer,
                        }),
                        input_trace::InputOrigin::User,
                    );
                }
                Input::Button(button, down) => {
                    let button = match button {
                        0 => MouseButton::Left,
                        1 => MouseButton::Right,
                        2 => MouseButton::Middle,
                        _ => continue,
                    };
                    self.handle_window_event(
                        events,
                        id,
                        WindowEvent::MouseInput {
                            device_id: DeviceId::dummy(),
                            state: if down {
                                ElementState::Pressed
                            } else {
                                ElementState::Released
                            },
                            button,
                        },
                        input_trace::InputOrigin::User,
                    );
                }
            }
        }
    }
}
