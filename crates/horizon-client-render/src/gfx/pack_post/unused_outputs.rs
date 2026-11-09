//! Fits a program's stage interface into the device's `max_inter_stage_shader_variables`.
//!
//! A pack's vertex stage may declare more varyings than a phone GPU allows (LIGHT-Shaders 1.9.0's
//! `final` writes one at location 28 where the Adreno 619 allows 27), though its fragment stage
//! reads only some of them. wgpu checks the location numbers, so dropping the outputs the
//! fragment stage never reads is not enough: the locations that remain are renumbered from 0, in
//! the same order in both stages. A program already within the limit is left untouched, and so
//! is one whose entry points don't have the shape the translator emits.

use std::collections::{BTreeMap, BTreeSet};

use naga::{Binding, Block, Expression, Handle, Module, ShaderStage, Statement, TypeInner};

/// The `@location` of a binding.
fn location(binding: &Option<Binding>) -> Option<u32> {
    match binding {
        Some(Binding::Location { location, .. }) => Some(*location),
        _ => None,
    }
}

/// Every `return` value of `block`, and whether all of them returned something.
fn return_values(block: &Block, values: &mut BTreeSet<Handle<Expression>>, all: &mut bool) {
    for statement in block.iter() {
        match statement {
            Statement::Return { value: Some(v) } => _ = values.insert(*v),
            Statement::Return { value: None } => *all = false,
            Statement::Block(b) => return_values(b, values, all),
            Statement::If { accept, reject, .. } => {
                return_values(accept, values, all);
                return_values(reject, values, all);
            }
            Statement::Switch { cases, .. } => {
                for case in cases {
                    return_values(&case.body, values, all);
                }
            }
            Statement::Loop {
                body, continuing, ..
            } => {
                return_values(body, values, all);
                return_values(continuing, values, all);
            }
            _ => {}
        }
    }
}

/// The locations the fragment stage takes as inputs: the arguments' own, and the members of a
/// struct argument.
fn fragment_inputs(fragment: &Module) -> Option<BTreeSet<u32>> {
    let ep = fragment
        .entry_points
        .iter()
        .find(|e| e.stage == ShaderStage::Fragment)?;
    let mut inputs = BTreeSet::new();
    for argument in &ep.function.arguments {
        if let Some(l) = location(&argument.binding) {
            inputs.insert(l);
        } else if argument.binding.is_none()
            && let TypeInner::Struct { members, .. } = &fragment.types[argument.ty].inner
        {
            inputs.extend(members.iter().filter_map(|m| location(&m.binding)));
        }
    }
    Some(inputs)
}

/// Points `binding` at `to[its location]`, when it is a location in `to`.
fn renumber(binding: &mut Option<Binding>, to: &BTreeMap<u32, u32>) {
    if let Some(Binding::Location { location, .. }) = binding
        && let Some(&new) = to.get(location)
    {
        *location = new;
    }
}

/// Makes the vertex stage of `vertex` and the fragment stage of `fragment` use at most `limit`
/// locations (numbered 0 to `limit` - 1) by dropping the vertex outputs the fragment stage does
/// not read and renumbering the rest. Returns how many outputs it dropped; 0 when nothing
/// needed to change (or could).
pub(super) fn fit(vertex: &mut Module, fragment: &mut Module, limit: u32) -> usize {
    let Some(inputs) = fragment_inputs(fragment) else {
        return 0;
    };
    let Some(vi) = vertex
        .entry_points
        .iter()
        .position(|e| e.stage == ShaderStage::Vertex)
    else {
        return 0;
    };
    let Some(result) = &vertex.entry_points[vi].function.result else {
        return 0;
    };
    let struct_ty = result.ty;
    let TypeInner::Struct { members, span } = &vertex.types[struct_ty].inner else {
        return 0;
    };
    let highest = members.iter().filter_map(|m| location(&m.binding)).max();
    if highest.is_none_or(|h| h < limit) {
        return 0;
    }
    // The locations that stay, and where they go: those the fragment stage reads, in order.
    let to: BTreeMap<u32, u32> = (inputs.iter().copied()).zip(0u32..).collect();
    if to.len() as u32 > limit {
        return 0;
    }
    // Which of the result struct's members stay.
    let keep: Vec<bool> = members
        .iter()
        .map(|m| location(&m.binding).is_none_or(|l| to.contains_key(&l)))
        .collect();
    let dropped = keep.iter().filter(|k| !**k).count();
    // The entry point must end in `return`s of a struct built from its parts, one component
    // for each member; anything else is left alone.
    let f = &vertex.entry_points[vi].function;
    let mut values = BTreeSet::new();
    let mut all = true;
    return_values(&f.body, &mut values, &mut all);
    let composes = |v: &Handle<Expression>| {
        matches!(
            &f.expressions[*v],
            Expression::Compose { ty, components }
                if *ty == struct_ty && components.len() == keep.len()
        )
    };
    if !all || values.is_empty() || !values.iter().all(composes) {
        return 0;
    }
    // Rewrite the struct, then the components of what is returned.
    let mut new_type = vertex.types[struct_ty].clone();
    let span = *span;
    if let TypeInner::Struct { members, .. } = &mut new_type.inner {
        let mut kept = Vec::with_capacity(members.len() - dropped);
        for (m, &stay) in members.drain(..).zip(&keep) {
            if stay {
                let mut m = m;
                renumber(&mut m.binding, &to);
                kept.push(m);
            }
        }
        new_type.inner = TypeInner::Struct {
            members: kept,
            span,
        };
    }
    vertex.types.replace(struct_ty, new_type);
    let f = &mut vertex.entry_points[vi].function;
    for v in values {
        if let Expression::Compose { components, .. } = &mut f.expressions[v] {
            let mut index = 0;
            components.retain(|_| {
                index += 1;
                keep[index - 1]
            });
        }
    }
    // The fragment stage's inputs, at their new locations.
    let Some(fi) = fragment
        .entry_points
        .iter()
        .position(|e| e.stage == ShaderStage::Fragment)
    else {
        return dropped;
    };
    // Each struct type once: two arguments of one type share its members.
    let struct_args: BTreeSet<Handle<naga::Type>> =
        (fragment.entry_points[fi].function.arguments.iter())
            .filter(|a| a.binding.is_none())
            .map(|a| a.ty)
            .collect();
    for argument in &mut fragment.entry_points[fi].function.arguments {
        renumber(&mut argument.binding, &to);
    }
    for ty in struct_args {
        let mut new_type = fragment.types[ty].clone();
        if let TypeInner::Struct { members, .. } = &mut new_type.inner {
            for m in members.iter_mut() {
                renumber(&mut m.binding, &to);
            }
            fragment.types.replace(ty, new_type);
        }
    }
    dropped
}
