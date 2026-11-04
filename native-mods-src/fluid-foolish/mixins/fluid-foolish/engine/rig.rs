//! Turns blended bone poses (the pack's Bedrock rig: root > waist > body/head/arms, legs under
//! root) into additions to Horizon's player model parts.
//!
//! Axis conversion, read from the pack's own clips: Bedrock's Y is up and Java's is down, so
//! positions are (x, -y, z). The sneak clip pushes the upper body forward (-Z, as in Java) and
//! leans it with +X rotation, as Java's +X does; the turn clips show +Y is a left turn where Java's
//! +Y turns right, so rotations are (x, -y, z).

use crate::clip::Pose;
use glam::{EulerRot, Mat4, Quat, Vec3};
use std::collections::BTreeMap;

/// Blended bone poses by bone name (`root`, `waist`, `body`, `head`, `left_arm`, ...).
pub type Rig = BTreeMap<String, Pose>;

/// An addition to one player model part: position in pixels, rotation in radians (X, Y, Z).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PartAdd {
    pub name: &'static str,
    pub position: [f32; 3],
    pub rotation: [f32; 3],
}

/// Java player model part, its model origin, the rig bone pivot (Java model pixels) and whether
/// it hangs under the waist.
const PARTS: [(&str, [f32; 3], [f32; 3], bool); 6] = [
    ("head", [0.0, 0.0, 0.0], [0.0, -4.0, 0.0], true),
    ("body", [0.0, 0.0, 0.0], [0.0, 6.0, 0.0], true),
    ("right_arm", [-5.0, 2.0, 0.0], [-5.0, 2.0, 0.0], true),
    ("left_arm", [5.0, 2.0, 0.0], [5.0, 2.0, 0.0], true),
    ("right_leg", [-1.9, 12.0, 0.0], [-2.0, 12.0, 0.0], false),
    ("left_leg", [1.9, 12.0, 0.0], [2.0, 12.0, 0.0], false),
];
const ROOT_PIVOT: [f32; 3] = [0.0, 24.0, 0.0];
const WAIST_PIVOT: [f32; 3] = [0.0, 12.0, 0.0];

/// `pose` as a transform about `pivot` (Java model pixels), Bedrock axes converted to Java's.
fn delta(pose: Option<&Pose>, pivot: [f32; 3]) -> Mat4 {
    let Some(p) = pose else { return Mat4::IDENTITY };
    let pivot = Vec3::from(pivot);
    let d = Vec3::new(p.position[0], -p.position[1], p.position[2]);
    let r = Quat::from_euler(
        EulerRot::ZYX,
        p.rotation[2].to_radians(),
        (-p.rotation[1]).to_radians(),
        p.rotation[0].to_radians(),
    );
    Mat4::from_translation(pivot) * Mat4::from_translation(d) * Mat4::from_quat(r) * Mat4::from_translation(-pivot)
}

/// What to add to each player model part for `rig`. Parts the rig leaves at rest are left out.
pub fn parts(rig: &Rig) -> Vec<PartAdd> {
    let root = delta(rig.get("root"), ROOT_PIVOT);
    let waist = delta(rig.get("waist"), WAIST_PIVOT);
    let mut out = Vec::new();
    for (name, origin, pivot, upper) in PARTS {
        let own = delta(rig.get(name), pivot);
        let m = if upper { root * waist * own } else { root * own };
        let o = Vec3::from(origin);
        let moved = m.transform_point3(o) - o;
        let (z, y, x) = Quat::from_mat4(&m).to_euler(EulerRot::ZYX);
        let add = PartAdd { name, position: moved.to_array(), rotation: [x, y, z] };
        let quiet = add.position.iter().chain(add.rotation.iter()).all(|v| v.abs() < 1.0e-5);
        if !quiet {
            out.push(add);
        }
    }
    out
}
