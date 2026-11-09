//! winit's `ApplicationHandler` for the game window: window and device events, shutting down,
//! and the idle step (`about_to_wait`).
use super::*;

impl Game<'_> {
    /// winit's `window_event`. The control socket and the pad menu call it too, with the
    /// input they make up.
    pub(super) fn handle_window_event(
        &mut self,
        event_loop: &dyn EventLoopCtl,
        id: WindowId,
        mut event: WindowEvent,
        origin: input_trace::InputOrigin,
    ) {
        // A headless game has no window: its events (the frame, the control socket's input)
        // carry `WindowId::dummy()`.
        let ours = match &self.window {
            Some(w) => w.id() == id,
            None => self.options.headless,
        };
        if !ours {
            return;
        }
        #[cfg(target_os = "android")]
        if let WindowEvent::KeyboardInput { event, .. } = &mut event
            && !matches!(event.physical_key, PhysicalKey::Code(_))
            && let Some(code) =
                horizon_client::keypad::resolve_key(event.physical_key, &event.logical_key)
        {
            event.physical_key = PhysicalKey::Code(code);
        }
        #[cfg(target_os = "android")]
        if let WindowEvent::KeyboardInput { event, .. } = &event
            && event.state == ElementState::Pressed
            && let PhysicalKey::Code(key) = event.physical_key
            && let Some(name) = horizon_client::keybinds::key_name(key)
            && self.pad.touch.capture_custom_key(name)
        {
            return;
        }
        #[cfg(target_os = "android")]
        if let WindowEvent::KeyboardInput { event, .. } = &event
            && let PhysicalKey::Code(key) = event.physical_key
        {
            if event.state == ElementState::Pressed {
                self.pad.hardware_keys.insert(key);
            } else {
                self.pad.hardware_keys.remove(&key);
            }
            if self.pad.custom_keys.contains(&key) {
                return;
            }
        }
        #[cfg(target_os = "android")]
        if let WindowEvent::CursorMoved { device_id, .. }
        | WindowEvent::MouseInput { device_id, .. }
        | WindowEvent::MouseWheel { device_id, .. } = &event
            && winit::platform::android::native_pointer_device(*device_id)
        {
            let before = crate::keyboard_mouse_input();
            let accepted = horizon_client::android_runtime::note_input(
                horizon_client::android_gamepad::InputMode::KeyboardMouse,
            );
            if !accepted && !self.pad_in_menu() {
                return;
            }
            if !before && crate::keyboard_mouse_input() && self.captured {
                self.capture(false);
                self.capture(true);
            }
        }
        #[cfg(target_os = "android")]
        match &event {
            WindowEvent::Touch(_) => crate::touch_input(true),
            WindowEvent::KeyboardInput {
                device_id,
                event,
                is_synthetic: false,
            } if event.state == ElementState::Pressed
                && winit::platform::android::native_device_id(*device_id) > 0 =>
            {
                crate::touch_input(false)
            }
            _ => {}
        }
        #[cfg(target_os = "android")]
        if !self.pad_in_menu()
            && matches!(
                horizon_client::android_runtime::input_mode(),
                horizon_client::android_gamepad::InputMode::Touch
                    | horizon_client::android_gamepad::InputMode::Controller
            )
            && matches!(&event, WindowEvent::KeyboardInput { event, .. }
                if event.state == ElementState::Pressed
                    && !matches!(event.logical_key, winit::keyboard::Key::Named(winit::keyboard::NamedKey::Escape | winit::keyboard::NamedKey::BrowserBack)))
        {
            return;
        }
        #[cfg(target_os = "android")]
        if let WindowEvent::KeyboardInput { event, .. } = &event {
            match self.android_keypad_menu_key(event_loop, event) {
                Ok(true) => return,
                Ok(false) => {}
                Err(error) => {
                    log::warn!("Keypad menu: {error}");
                    return;
                }
            }
        }
        #[cfg(target_os = "android")]
        if self.android_aux_mouse(event_loop, id, &event) {
            return;
        }
        #[cfg(target_os = "android")]
        if let WindowEvent::KeyboardInput { event, .. } = &event
            && let Some(key) =
                horizon_client::keypad::resolve_key(event.physical_key, &event.logical_key)
            && (!self.pad_in_menu() || event.state == ElementState::Released)
            && horizon_client::android_runtime::keypad_key(
                key,
                event.state == ElementState::Pressed,
            )
        {
            return;
        }
        #[cfg(target_os = "android")]
        if matches!(&event,WindowEvent::KeyboardInput {event,..} if event.state==ElementState::Pressed && event.logical_key==winit::keyboard::Key::Named(winit::keyboard::NamedKey::BrowserBack))
        {
            if self.pad.touch.editor.is_some() {
                self.pad.touch.cancel_editor();
                self.settings.touch = self.pad.touch.settings.clone();
                self.pause = false;
                return;
            }
            if crate::platform::active() {
                crate::platform::hide();
                return;
            }
            self.pad.touch.clear();
            crate::platform::hide();
            if let Err(e) = self.pad_back() {
                log::warn!("Android Back: {e}");
            }
            return;
        }
        #[cfg(target_os = "android")]
        if self.pad.touch.editor.is_some()
            && matches!(&event,WindowEvent::KeyboardInput{event,..}
            if event.state==ElementState::Pressed && event.physical_key==PhysicalKey::Code(KeyCode::Escape))
        {
            self.pad.touch.cancel_editor();
            self.settings.touch = self.pad.touch.settings.clone();
            self.pause = false;
            return;
        }
        #[cfg(target_os = "android")]
        if let WindowEvent::Touch(touch) = &event {
            use winit::event::TouchPhase;
            self.last_input = Instant::now();
            self.pad.menu.keyboard_focus = false;
            let size = self.window.as_ref().unwrap().inner_size();
            self.pad.touch.viewport(size.width, size.height);
            if self.pad.touch.editor.is_some() {
                if touch.phase == TouchPhase::Cancelled {
                    self.pad.touch.cancel_editor();
                    self.pause = false;
                } else if self.pad.touch.edit_touch(
                    touch.id,
                    [
                        touch.location.x as f32 / size.width.max(1) as f32,
                        touch.location.y as f32 / size.height.max(1) as f32,
                    ],
                    touch.phase == TouchPhase::Started,
                    touch.phase == TouchPhase::Ended,
                ) {
                    self.pause = false;
                }
                if self.pad.touch.editor.is_none() {
                    self.settings.touch = self.pad.touch.settings.clone();
                }
                if std::mem::take(&mut self.pad.touch.clicked) {
                    self.audio.play_button_click();
                }
                return;
            }
            match self.android_aux_touch(touch, event_loop, id) {
                Ok(true) => return,
                Ok(false) => {}
                Err(error) => {
                    log::error!("Android input: {error}");
                    return;
                }
            }
            if touch.phase == TouchPhase::Cancelled {
                self.pad.touch.clear();
                return;
            }
            let overlay_point = [
                touch.location.x as f32 / size.width.max(1) as f32,
                touch.location.y as f32 / size.height.max(1) as f32,
            ];
            let inventory_control = self.android_inventory_controls()
                && (self.pad.touch.owns_pointer(touch.id)
                    || (touch.phase == TouchPhase::Started
                        && self.pad.touch.control_at_point(overlay_point)));
            if inventory_control
                && self.pad.touch.inventory_update(
                    touch.id,
                    overlay_point,
                    touch.phase == TouchPhase::Started,
                    matches!(touch.phase, TouchPhase::Ended | TouchPhase::Cancelled),
                )
            {
                self.android_custom_events();
                return;
            }
            if inventory_control
                && touch.phase == TouchPhase::Started
                && self.pad.touch.custom_at(overlay_point).is_none()
                && let Some(control) = self.pad.touch.control_at(overlay_point)
                && !matches!(
                    control,
                    horizon_client::touch::Control::Debug
                        | horizon_client::touch::Control::Settings
                        | horizon_client::touch::Control::Perspective
                )
            {
                self.pad.touch.ignore(touch.id);
                if matches!(
                    control,
                    horizon_client::touch::Control::Inventory
                        | horizon_client::touch::Control::Pause
                ) && let Err(error) = self.pad_back()
                {
                    log::warn!("Touch inventory close: {error}");
                }
                return;
            }
            if !self.pad_in_menu() || inventory_control {
                if !crate::touch_visible() && !self.settings.keypad.enabled {
                    return;
                }
                self.pad.in_use = true;
                let size = self.window.as_ref().unwrap().inner_size();
                if touch.phase == TouchPhase::Started {
                    let batch = HudBatch::new([size.width, size.height]);
                    let point = [
                        touch.location.x as f32 / batch.scale,
                        touch.location.y as f32 / batch.scale,
                    ];
                    if let Some(slot) =
                        horizon_client::touch::hotbar_slot(batch.width, batch.height, point)
                        && !self.pad.touch.control_at_point([
                            touch.location.x as f32 / size.width.max(1) as f32,
                            touch.location.y as f32 / size.height.max(1) as f32,
                        ])
                    {
                        self.selected = slot;
                        self.pad.touch.ignore(touch.id);
                        return;
                    }
                }
                self.pad.touch.projectile_crosshair = self.touch_projectile_crosshair();
                self.pad.touch.hold_uses_item = self.touch_item_held_use();
                let delta = self.pad.touch.update(
                    touch.id,
                    [
                        touch.location.x as f32 / size.width.max(1) as f32,
                        touch.location.y as f32 / size.height.max(1) as f32,
                    ],
                    touch.phase == TouchPhase::Started,
                    matches!(touch.phase, TouchPhase::Ended | TouchPhase::Cancelled),
                );
                self.android_custom_events();
                let delta = horizon_client::touch::camera_delta(
                    delta,
                    size.width,
                    size.height,
                    &self.pad.touch.settings,
                );
                self.tutorial
                    .mouse(f64::from(delta[0]), f64::from(delta[1]));
                self.yaw += delta[0];
                self.pitch = (self.pitch + delta[1]).clamp(-90.0, 90.0);
                if self.pad.touch.chat_requested {
                    self.audio.play_button_click();
                    self.pad.touch.clear();
                    if let Err(error) = self.key_pressed(KeyCode::KeyT, KeyCode::KeyT) {
                        log::warn!("Touch chat: {error}");
                    }
                    self.android_show_keyboard();
                } else if self.pad.touch.settings_requested {
                    self.android_release_touch_keys(true);
                    self.audio.play_button_click();
                    self.open_options(false);
                } else if std::mem::take(&mut self.pad.touch.debug_requested) {
                    self.audio.play_button_click();
                    if !self.pad.touch_ui.key_held(KeyCode::F3) {
                        use horizon_client::debug_keys::KeyAction;
                        self.debug_key(KeyCode::F3, KeyAction::Press);
                        self.debug_key(KeyCode::F3, KeyAction::Release);
                    }
                } else if std::mem::take(&mut self.pad.touch.perspective_requested) {
                    self.audio.play_button_click();
                    if let Err(error) = self.key_pressed(KeyCode::F5, KeyCode::F5) {
                        log::warn!("Touch perspective: {error}");
                    }
                }
            } else {
                if !self.android_inventory_controls() {
                    self.pad.touch.clear();
                }
                if !matches!(self.screen, Screen::Inventory | Screen::Creative) {
                    self.android_menu_touch(touch, event_loop, id);
                    return;
                }
                if self.pad.touch_ui.virtual_mouse {
                    self.android_virtual_touch(touch, event_loop, id);
                    return;
                }
                self.handle_window_event(
                    event_loop,
                    id,
                    WindowEvent::CursorMoved {
                        device_id: touch.device_id,
                        position: touch.location,
                    }, input_trace::InputOrigin::User);
                if touch.phase != TouchPhase::Moved {
                    self.handle_window_event(
                        event_loop,
                        id,
                        WindowEvent::MouseInput {
                            device_id: touch.device_id,
                            button: MouseButton::Left,
                            state: if touch.phase == TouchPhase::Started {
                                ElementState::Pressed
                            } else {
                                ElementState::Released
                            },
                        }, input_trace::InputOrigin::User);
                }
                if touch.phase == TouchPhase::Ended {
                    let b = self.gui_scale();
                    if let Some(b) = b {
                        let p = [
                            touch.location.x as f32 / b.scale,
                            touch.location.y as f32 / b.scale,
                        ];
                        if crate::platform::field_at(p) {
                            self.android_show_keyboard();
                        }
                    }
                }
            }
            return;
        }
        follow_keymap(&mut event);
        // MacosUtil.setCtrlClickEmulatesRightClick: Control + left click is a right click.
        if let WindowEvent::MouseInput { state, button, .. } = &mut event
            && *button == MouseButton::Left
            && self.right_click_emulation.is_right_click(
                *state == ElementState::Pressed,
                self.modifiers.control_key(),
                cfg!(target_os = "macos") && self.settings.ctrl_click_emulates_right_click,
            )
        {
            *button = MouseButton::Right;
        }
        // Who caused it, before the event is handled: the trace wants the
        // window's own keys, never the ones the socket made up.
        self.trace_window_event(origin, &event);
        if let WindowEvent::KeyboardInput { event, .. } = &event
            && let PhysicalKey::Code(key) = event.physical_key
        {
            if event.state == ElementState::Pressed {
                self.physical_keys.insert(key);
                horizon_client::keybinds::learn_layout(event);
            } else {
                self.physical_keys.remove(&key);
            }
        }
        if matches!(
            &event,
            WindowEvent::MouseInput {
                state: ElementState::Pressed,
                ..
            }
        ) && !self.pad.menu.synthetic
        {
            self.pad.keyboard_took_over = true;
        }
        if let WindowEvent::MouseInput { state, button, .. } = &event {
            if *state == ElementState::Pressed {
                self.physical_buttons.insert(*button);
            } else {
                self.physical_buttons.remove(button);
            }
        }
        let mouse = if !self.pad_synthetic_input()
            && let WindowEvent::MouseInput { state, button, .. } = &event
        {
            Some((
                glfw_button(*button),
                *state == ElementState::Pressed,
                glfw_modifiers(self.modifiers),
            ))
        } else {
            None
        };
        if let Some((button, pressed, modifiers)) = mouse {
            let mut canceled = false;
            self.native_mouse_button_pre(button, pressed, modifiers, &mut canceled);
            if canceled {
                return;
            }
        }
        self.handle_window_event_inner(event_loop, event);
        if let Some((button, pressed, modifiers)) = mouse {
            self.native_mouse_button_post(button, pressed, modifiers);
        }
    }

    /// The loop ended: close the pads' output, save the world, write the perf report.
    pub(super) fn shut_down(&mut self) {
        self.end_screenshot_sequence(Err("cancelled: the game is closing".into()));
        self.pad_close_output();
        if let Err(e) = self.save_for_exit() {
            log::warn!("Horizon: failed to save the world on exit: {e}");
        }
        self.finish_perf();
    }
    /// winit's `about_to_wait`: what happens between frames (stop requests, the closing
    /// save, world entry, the control socket, pads), then the next frame.
    pub(super) fn idle(&mut self, event_loop: &dyn EventLoopCtl) {
        // A splitscreen player's game has nothing to save: P1's shuts the process down.
        if horizon_threads::stop_requests() > 0
            && !self.options.split_guest
            && self.pending_save.as_ref().is_none_or(|save| !save.quit)
        {
            // SIGTERM, SIGINT or SIGHUP: the shutdown hook's server halt, as a window close.
            if let Err(error) = self.begin_closing_save(true) {
                self.error = Some(error);
                crate::game::exit_app(event_loop);
                return;
            }
        }
        if self.pending_save.is_some() {
            match self.poll_closing_save() {
                Ok(true) => crate::game::exit_app(event_loop),
                Ok(false) => {
                    if let Some(window) = &self.window {
                        window.request_redraw();
                    }
                    // Yield between polls even when disk I/O is slow.
                    event_loop.set_control_flow(ControlFlow::WaitUntil(
                        Instant::now() + Duration::from_millis(4),
                    ));
                }
                Err(error) => {
                    self.error = Some(error);
                    self.exit_on_error(event_loop);
                }
            }
            return;
        }
        if let Err(error) = horizon_client::spans::time("world_entry", || self.resume_world_entry())
        {
            self.error = Some(error);
            self.exit_on_error(event_loop);
            return;
        }
        // The world a backup made before its upgrade opens (EditWorldScreen's callback).
        if let Some(folder) = self.title.as_mut().and_then(|t| t.take_open_request())
            && let Err(error) = self.open_world(&folder, None, None, false, None)
        {
            self.error = Some(error);
            event_loop.exit();
            return;
        }
        // A Realm's address came back (GetServerDetailsTask hands over to ConnectScreen).
        if let Some(action) = self.title.as_mut().and_then(|t| t.take_connect_request())
            && let Err(error) = self.title_action(event_loop, action)
        {
            self.error = Some(error);
            event_loop.exit();
            return;
        }
        horizon_client::spans::time("server_packs", || self.tick_server_packs());
        horizon_client::spans::time("control", || self.poll_control(event_loop));
        #[cfg(target_os = "android")]
        self.poll_touch_overlay(event_loop);
        if self.pending_save.is_some() {
            return;
        }
        horizon_client::spans::time("pads", || self.poll_pads(event_loop));
        self.pause_if_inactive();
        if self.options.frames.is_some() && self.started.elapsed() > self.options.timeout {
            self.error = Some("frame-limited run timed out".into());
            crate::game::exit_app(event_loop);
            return;
        }
        if let Some(w) = &self.window {
            if self.options.offscreen {
                // Offscreen frames don't wait for the window to be drawn: an
                // occluded window (a locked macOS session) gets no redraws.
                let id = w.id();
                self.handle_window_event(
                    event_loop,
                    id,
                    WindowEvent::RedrawRequested,
                    input_trace::InputOrigin::Game,
                );
            } else {
                w.request_redraw();
            }
        } else if self.options.headless {
            // The headless loop paces the frames itself (`run_headless`).
            self.handle_window_event(
                event_loop,
                WindowId::dummy(),
                WindowEvent::RedrawRequested,
                input_trace::InputOrigin::Game,
            );
            return;
        }
        // Consume the last frame's result: a hidden compositor may stop sending
        // redraws altogether, leaving a successful result stale indefinitely.
        event_loop.set_control_flow(event_loop::redraw_flow(
            &mut self.frame_drawn,
            self.options.offscreen,
            Instant::now(),
        ));
    }
}

impl ApplicationHandler for Game<'_> {
    #[cfg(target_os = "android")]
    fn suspended(&mut self, _event_loop: &ActiveEventLoop) {
        // Android may recreate the surface while the IME changes window size.
        // Preserve the text session; Android hides its window on real backgrounding.
        self.android_release_touch_keys(true);
        self.capture(false);
        self.keys.clear();
        self.physical_keys.clear();
        self.pad.touch.clear();
        if self.title.is_none() && self.screen == Screen::Playing {
            self.screen = Screen::Paused;
        }
        if let Some(renderer) = &mut self.renderer {
            renderer.suspend_surface();
        }
    }
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        #[cfg(target_os = "android")]
        if let Some(renderer) = &mut self.renderer {
            if let Some(window) = &self.window {
                match crate::display::prepare(window) {
                    Ok(Some(size)) => renderer.resize(size[0], size[1]),
                    Ok(None) => {}
                    Err(error) => log::warn!("Android display scale on resume: {error}"),
                }
            }
            if let Err(error) = renderer.recreate_surface() {
                self.error = Some(error);
                crate::game::exit_app(event_loop);
            } else if let Some(window) = &self.window {
                let size = window.inner_size();
                renderer.resize(size.width, size.height);
            }
            self.previous = Instant::now();
            return;
        }
        if self.window.is_some() {
            return;
        }
        debug_keys::warm_debug_cpu();
        // The Window constructor: created fullscreen when options.txt or --fullscreen says so.
        let fullscreen = self.settings.fullscreen || self.options.fullscreen;
        let size = self.options.window_size.map(f64::from);
        self.fullscreen = horizon_client::window::FullscreenState::new(fullscreen);
        let attributes = horizon_client::window::with_horizon_icon(
            Window::default_attributes().with_title("Horizon"),
        );
        // Wayland names the window by its app id, which the horizon.desktop entry matches.
        #[cfg(target_os = "linux")]
        let attributes = winit::platform::wayland::WindowAttributesExtWayland::with_name(
            attributes, "horizon", "horizon",
        );
        // `--offscreen` renders into a texture: its window would only show white.
        let window_step = startup::step("window.create");
        let window = match event_loop.create_window(
            attributes
                .with_inner_size(winit::dpi::LogicalSize::new(size[0], size[1]))
                .with_fullscreen(fullscreen.then_some(winit::window::Fullscreen::Borderless(None)))
                .with_visible(!self.options.offscreen),
        ) {
            Ok(w) => Arc::new(w),
            Err(e) => {
                self.error = Some(e.into());
                event_loop.exit();
                return;
            }
        };
        horizon_client::window::settle_created_fullscreen(&window, fullscreen);
        drop(window_step);
        horizon_client::telemetry::note_display(&window);
        match self.new_renderer(window.clone()) {
            Ok(r) => {
                self.renderer = Some(r);
                self.world_renderer_ready = true;
            }
            Err(e) => {
                self.error = Some(e);
                crate::game::exit_app(event_loop);
                return;
            }
        }
        self.window = Some(window);
        self.ime_allowed = false;
        self.ime_area = None;
        self.apply_settings();
        self.previous = Instant::now();
    }
    fn window_event(&mut self, event_loop: &ActiveEventLoop, id: WindowId, event: WindowEvent) {
        let key = if self.window.as_ref().is_some_and(|w| w.id() == id)
            && let WindowEvent::KeyboardInput {
                event,
                is_synthetic: false,
                ..
            } = &event
        {
            Some((event.clone(), glfw_modifiers(self.modifiers)))
        } else {
            None
        };
        self.handle_window_event(event_loop, id, event, input_trace::InputOrigin::User);
        if let Some((event, modifiers)) = key {
            self.native_input_key(&event, modifiers);
        }
    }
    fn device_event(&mut self, event_loop: &ActiveEventLoop, device: DeviceId, event: DeviceEvent) {
        #[cfg(target_os = "android")]
        if self.focused
            && self.settings.controller.enabled
            && horizon_client::android_runtime::gamepad_event(
                winit::platform::android::native_device_id(device),
                &event,
            )
        {
            return;
        }
        #[cfg(not(target_os = "android"))]
        let _ = device;
        // A composited view renders offscreen but its keyboard/mouse owner still
        // controls a visible native window. A headless offscreen run never does.
        if self.pending_save.is_none()
            && self.captured
            && self.focused
            && self.keyboard_mouse
            && (!self.options.offscreen || self.split_window)
            && self.screen == Screen::Playing
            && let DeviceEvent::MouseMotion { delta: (x, y) } = event
        {
            if x != 0.0 || y != 0.0 {
                self.pad_yield_input(event_loop);
            }
            // MouseHandler.onMove with the pointer grabbed; applied once per frame.
            #[cfg(target_os = "android")]
            {
                if !horizon_client::android_runtime::note_input(
                    horizon_client::android_gamepad::InputMode::KeyboardMouse,
                ) {
                    return;
                }
            }
            self.note_input(
                input_trace::InputOrigin::User,
                input_trace::TraceKind::Motion { x, y },
            );
            self.mouse.on_move(0.0, 0.0, x, y, true, true);
            self.last_input = Instant::now();
        }
    }
    fn about_to_wait(&mut self, event_loop: &ActiveEventLoop) {
        #[cfg(target_os = "android")]
        self.android_apply_display_scale();
        #[cfg(target_os = "android")]
        self.android_poll_keyboard();
        #[cfg(target_os = "android")]
        if let Some(renderer) = &mut self.renderer {
            renderer.set_detailed_gpu_timing(
                crate::performance::active() || crate::device_runtime::preferences().gpu_profile,
            );
        }
        self.idle(event_loop);
    }
    /// The application is ending. On macOS, Quit (Cmd+Q) ends it through
    /// applicationWillTerminate without a CloseRequested; vanilla's GLFW
    /// window turns it into a window close, so Minecraft.stop saves the world.
    fn exiting(&mut self, _event_loop: &ActiveEventLoop) {
        self.shut_down();
    }
}
