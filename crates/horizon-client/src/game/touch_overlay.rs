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
}

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
/// The game surface's size and GUI scale, published by the game thread every frame.
static SURFACE_W: AtomicF32 = AtomicF32::new();
static SURFACE_H: AtomicF32 = AtomicF32::new();
static GUI_SCALE: AtomicF32 = AtomicF32::new();
/// The mouse is grabbed for play (the overlay then sends look motion, not a cursor).
static CAPTURED: AtomicBool = AtomicBool::new(false);
/// The overlay is showing (`nativeSetActive`), and whether the game has switched to mouse and
/// keyboard input for it yet: once per showing, so a pad picked up later still takes over.
static ACTIVE: AtomicBool = AtomicBool::new(false);
static ACTIVE_APPLIED: AtomicBool = AtomicBool::new(false);

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
        _ => {}
    }
    let motion = matches!(
        input,
        Input::Look(..) | Input::Wheel(_) | Input::Cursor(..)
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
    let (vw, vh) = (VIEW_W.get(), VIEW_H.get());
    let (sw, sh) = (SURFACE_W.get(), SURFACE_H.get());
    if vw <= 0.0 || vh <= 0.0 || sw <= 0.0 || sh <= 0.0 {
        return (f64::from(x), f64::from(y));
    }
    (f64::from(x / vw * sw), f64::from(y / vh * sh))
}

/// The hotbar slot (0..9) under a point of the overlay view, or -1: vanilla's 182x22 bar centred
/// at the bottom of the screen, drawn at the GUI scale.
fn hotbar_slot(x: f32, y: f32) -> i32 {
    let gui = GUI_SCALE.get();
    let (sw, sh) = (SURFACE_W.get(), SURFACE_H.get());
    if gui <= 0.0 || sw <= 0.0 || sh <= 0.0 {
        return -1;
    }
    let (px, py) = surface_point(x, y);
    let (px, py) = (px as f32, py as f32);
    if py < sh - 22.0 * gui || py > sh {
        return -1;
    }
    let along = (px - (sw / 2.0 - 91.0 * gui)) / gui;
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
            GUI_SCALE.set(horizon_client::hud::HudBatch::new(size).scale as f32);
        }
        CAPTURED.store(
            self.captured && self.screen == Screen::Playing,
            Ordering::Relaxed,
        );
        // The overlay replaces the game's own touch HUD. Only an automatic input mode follows
        // along: a mode the player fixed in Settings is theirs.
        let active = ACTIVE.load(Ordering::Relaxed);
        if active != ACTIVE_APPLIED.swap(active, Ordering::Relaxed) && active {
            horizon_client::android_runtime::note_input(
                horizon_client::android_gamepad::InputMode::KeyboardMouse,
            );
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
