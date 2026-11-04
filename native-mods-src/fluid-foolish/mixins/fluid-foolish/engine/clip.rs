//! Bedrock-style animation clips: position, rotation (degrees) and scale tracks per bone, with
//! linear and Catmull-Rom keyframes. The pack uses no molang, so values are plain numbers.

use serde_json::Value;
use std::collections::BTreeMap;

/// One bone's sampled transform: position in pixels, rotation in degrees, scale.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Pose {
    pub position: [f32; 3],
    pub rotation: [f32; 3],
    pub scale: [f32; 3],
}
impl Default for Pose {
    fn default() -> Self {
        Self { position: [0.0; 3], rotation: [0.0; 3], scale: [1.0; 3] }
    }
}

#[derive(Clone, Debug)]
struct Key {
    time: f32,
    pre: [f32; 3],
    post: [f32; 3],
    curve: bool,
}
#[derive(Clone, Debug)]
enum Channel {
    Const([f32; 3]),
    Keys(Vec<Key>),
}

#[derive(Clone, Debug, Default)]
struct Track {
    position: Option<Channel>,
    rotation: Option<Channel>,
    scale: Option<Channel>,
}

#[derive(Clone, Debug)]
pub struct Clip {
    pub name: String,
    pub length: f32,
    pub looped: bool,
    pub hold_on_last_frame: bool,
    tracks: BTreeMap<String, Track>,
}

/// Every clip of the pack, by name.
#[derive(Clone, Debug, Default)]
pub struct Clips {
    clips: BTreeMap<String, Clip>,
}

fn vec3(v: &Value) -> Option<[f32; 3]> {
    match v {
        Value::Number(n) => n.as_f64().map(|n| [n as f32; 3]),
        Value::Array(a) => {
            let nums: Vec<f32> = a.iter().filter_map(|x| x.as_f64()).map(|x| x as f32).collect();
            match nums.len() {
                1 => Some([nums[0]; 3]),
                3 => Some([nums[0], nums[1], nums[2]]),
                _ => None,
            }
        }
        _ => None,
    }
}

fn channel(v: &Value) -> Option<Channel> {
    if let Some(c) = vec3(v) {
        return Some(Channel::Const(c));
    }
    let map = v.as_object()?;
    let mut keys: Vec<Key> = Vec::new();
    for (time, frame) in map {
        let Ok(time) = time.parse::<f32>() else { continue };
        let key = if let Some(c) = vec3(frame) {
            Key { time, pre: c, post: c, curve: false }
        } else if let Some(o) = frame.as_object() {
            let post = o.get("post").and_then(vec3);
            let pre = o.get("pre").and_then(vec3);
            let (Some(first), true) = (post.or(pre), true) else { continue };
            let curve = o.get("lerp_mode").and_then(Value::as_str) == Some("catmullrom");
            Key { time, pre: pre.unwrap_or(first), post: post.unwrap_or(first), curve }
        } else {
            continue;
        };
        keys.push(key);
    }
    if keys.is_empty() {
        return None;
    }
    keys.sort_by(|a, b| a.time.total_cmp(&b.time));
    Some(Channel::Keys(keys))
}

fn catmull(p0: [f32; 3], p1: [f32; 3], p2: [f32; 3], p3: [f32; 3], u: f32) -> [f32; 3] {
    let mut out = [0.0; 3];
    for i in 0..3 {
        out[i] = 0.5
            * (2.0 * p1[i]
                + (-p0[i] + p2[i]) * u
                + (2.0 * p0[i] - 5.0 * p1[i] + 4.0 * p2[i] - p3[i]) * u * u
                + (-p0[i] + 3.0 * p1[i] - 3.0 * p2[i] + p3[i]) * u * u * u);
    }
    out
}

impl Channel {
    fn last_time(&self) -> f32 {
        match self {
            Channel::Const(_) => 0.0,
            Channel::Keys(k) => k.last().map_or(0.0, |k| k.time),
        }
    }
    fn sample(&self, t: f32) -> [f32; 3] {
        let keys = match self {
            Channel::Const(c) => return *c,
            Channel::Keys(k) => k,
        };
        let first = &keys[0];
        if t <= first.time {
            return first.post;
        }
        let last = &keys[keys.len() - 1];
        if t >= last.time {
            return last.post;
        }
        let i = keys.partition_point(|k| k.time <= t) - 1;
        let (a, b) = (&keys[i], &keys[i + 1]);
        let span = b.time - a.time;
        let u = if span > 0.0 { (t - a.time) / span } else { 1.0 };
        if a.curve || b.curve {
            let before = if i > 0 { keys[i - 1].post } else { a.post };
            let after = keys.get(i + 2).map_or(b.pre, |k| k.pre);
            return catmull(before, a.post, b.pre, after, u);
        }
        let mut out = [0.0; 3];
        for n in 0..3 {
            out[n] = a.post[n] + (b.pre[n] - a.post[n]) * u;
        }
        out
    }
}

impl Clip {
    fn parse(name: &str, data: &serde_json::Map<String, Value>) -> Clip {
        let mut tracks = BTreeMap::new();
        let mut last = 0.0f32;
        if let Some(bones) = data.get("bones").and_then(Value::as_object) {
            for (bone, channels) in bones {
                let Some(channels) = channels.as_object() else { continue };
                let track = Track {
                    position: channels.get("position").and_then(channel),
                    rotation: channels.get("rotation").and_then(channel),
                    scale: channels.get("scale").and_then(channel),
                };
                for c in [&track.position, &track.rotation, &track.scale].into_iter().flatten() {
                    last = last.max(c.last_time());
                }
                tracks.insert(bone.clone(), track);
            }
        }
        let loop_value = data.get("loop");
        let hold = loop_value.and_then(Value::as_str) == Some("hold_on_last_frame");
        let looped = loop_value.and_then(Value::as_bool).unwrap_or(false);
        let length = data
            .get("animation_length")
            .and_then(Value::as_f64)
            .map_or(last, |l| l as f32);
        Clip { name: name.to_owned(), length, looped, hold_on_last_frame: hold, tracks }
    }

    /// `time` (seconds since the clip started) as a time inside the clip: wrapped when it loops,
    /// held at the ends otherwise.
    pub fn local_time(&self, time: f32) -> f32 {
        if self.length <= 0.0 {
            return 0.0;
        }
        if self.looped {
            time.rem_euclid(self.length)
        } else {
            time.clamp(0.0, self.length)
        }
    }

    pub fn has_bone(&self, bone: &str) -> bool {
        self.tracks.contains_key(bone)
    }

    /// The bone's pose `time` seconds into the clip. A bone the clip does not animate rests.
    pub fn sample(&self, bone: &str, time: f32) -> Pose {
        let t = self.local_time(time);
        let Some(track) = self.tracks.get(bone) else { return Pose::default() };
        let mut pose = Pose::default();
        if let Some(c) = &track.position {
            pose.position = c.sample(t);
        }
        if let Some(c) = &track.rotation {
            pose.rotation = c.sample(t);
        }
        if let Some(c) = &track.scale {
            pose.scale = c.sample(t);
        }
        pose
    }
}

impl Clips {
    /// Reads `{"animations": {name: clip, ...}}`.
    pub fn parse(bytes: &[u8]) -> Result<Clips, String> {
        let json: Value = serde_json::from_slice(bytes).map_err(|e| e.to_string())?;
        let animations = json
            .get("animations")
            .and_then(Value::as_object)
            .ok_or("no animations")?;
        let mut clips = BTreeMap::new();
        for (name, value) in animations {
            if let Some(data) = value.as_object() {
                clips.insert(name.clone(), Clip::parse(name, data));
            }
        }
        Ok(Clips { clips })
    }
    pub fn get(&self, name: &str) -> Option<&Clip> {
        self.clips.get(name)
    }
    pub fn len(&self) -> usize {
        self.clips.len()
    }
    pub fn is_empty(&self) -> bool {
        self.clips.is_empty()
    }
}
