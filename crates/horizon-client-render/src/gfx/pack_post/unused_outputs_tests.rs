//! `unused_outputs::fit`: a vertex stage with more varyings than the device allows, whose fragment
//! stage reads three of them, runs within the limit once the rest are dropped; a program already
//! within it is left alone. Both modules must still validate. No GPU is needed.

use super::unused_outputs::fit;
use naga::{Binding, Module, ShaderStage, TypeInner};

const WRITTEN: u32 = 30;

fn vertex() -> Module {
    let mut text = String::from("#version 450\n");
    for i in 0..WRITTEN {
        text += &format!("layout(location = {i}) out float v{i};\n");
    }
    text += "void main() {\n";
    for i in 0..WRITTEN {
        text += &format!("    v{i} = {i}.0;\n");
    }
    text += "    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);\n}\n";
    naga::front::glsl::Frontend::default()
        .parse(
            &naga::front::glsl::Options::from(ShaderStage::Vertex),
            &text,
        )
        .unwrap()
}

fn fragment() -> Module {
    let text = "#version 450\n\
        layout(location = 3) in float a;\n\
        layout(location = 28) in float b;\n\
        layout(location = 29) in float c;\n\
        layout(location = 0) out vec4 color;\n\
        void main() { color = vec4(a, b, c, 1.0); }\n";
    naga::front::glsl::Frontend::default()
        .parse(
            &naga::front::glsl::Options::from(ShaderStage::Fragment),
            text,
        )
        .unwrap()
}

fn locations(bindings: impl Iterator<Item = Option<Binding>>) -> Vec<u32> {
    let mut out: Vec<u32> = bindings
        .filter_map(|b| match b {
            Some(Binding::Location { location, .. }) => Some(location),
            _ => None,
        })
        .collect();
    out.sort_unstable();
    out
}

fn vertex_outputs(module: &Module) -> Vec<u32> {
    let ep = (module.entry_points.iter())
        .find(|e| e.stage == ShaderStage::Vertex)
        .unwrap();
    let ty = ep.function.result.as_ref().unwrap().ty;
    let TypeInner::Struct { members, .. } = &module.types[ty].inner else {
        panic!("the vertex result is not a struct");
    };
    locations(members.iter().map(|m| m.binding.clone()))
}

fn fragment_inputs(module: &Module) -> Vec<u32> {
    let ep = (module.entry_points.iter())
        .find(|e| e.stage == ShaderStage::Fragment)
        .unwrap();
    let mut out = Vec::new();
    for argument in &ep.function.arguments {
        if argument.binding.is_some() {
            out.extend(locations(std::iter::once(argument.binding.clone())));
        } else if let TypeInner::Struct { members, .. } = &module.types[argument.ty].inner {
            out.extend(locations(members.iter().map(|m| m.binding.clone())));
        }
    }
    out.sort_unstable();
    out
}

fn validate(module: &Module) {
    naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::all(),
    )
    .validate(module)
    .unwrap();
}

#[test]
fn drops_unread_outputs_and_renumbers_both_stages() {
    let (mut vs, mut fs) = (vertex(), fragment());
    assert_eq!(vertex_outputs(&vs).len(), WRITTEN as usize);
    let dropped = fit(&mut vs, &mut fs, 27);
    assert_eq!(dropped, WRITTEN as usize - 3);
    assert_eq!(vertex_outputs(&vs), [0, 1, 2]);
    assert_eq!(fragment_inputs(&fs), [0, 1, 2]);
    validate(&vs);
    validate(&fs);
}

#[test]
fn a_program_within_the_limit_is_left_alone() {
    let (mut vs, mut fs) = (vertex(), fragment());
    assert_eq!(fit(&mut vs, &mut fs, 32), 0);
    assert_eq!(vertex_outputs(&vs).len(), WRITTEN as usize);
    assert_eq!(fragment_inputs(&fs), [3, 28, 29]);
}

#[test]
fn a_fragment_stage_that_needs_more_than_the_limit_is_left_alone() {
    let (mut vs, mut fs) = (vertex(), fragment());
    assert_eq!(fit(&mut vs, &mut fs, 2), 0);
    assert_eq!(vertex_outputs(&vs).len(), WRITTEN as usize);
    assert_eq!(fragment_inputs(&fs), [3, 28, 29]);
}
