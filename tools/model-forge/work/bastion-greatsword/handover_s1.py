#!/usr/bin/env python3
"""Held item vs. S1 rig sword in the same world frame (caster feet, yaw 0: x = left, y = up, z = forward).

Third-person chain verified against the 26.3 client bytecode (unobfuscated):
  LivingEntityRenderer: rotY(180-body) . scale(-1,-1,1) . AvatarRenderer.scale(0.9375) . translate(0,-1.501,0)
  PlayerModel.translateToHand: root . arm pivot (-5,2,0)px (slim: x += 0.5 for the right arm) . rotZYX(zRot,yRot,xRot)
  HumanoidModel ITEM pose: arm xRot = xRot*0.5 - pi/10 (-18 deg), yRot 0; bobArms adds zRot 0..0.1 rad (mean 0.05)
  ItemInHandLayer: rotX(-90) . rotY(180) . translate(+-1/16, 2/16, -10/16)
  ItemTransform: translate(T/16) . rotXYZ(R) . scale(s) ; ItemRenderer translate(-0.5,-0.5,-0.5); vertex/16
Rig: world = pivot + tilt*euler(key)*rest * p, euler(yaw,pitch,roll) = Ry(-yaw) Rx(pitch) Rz(roll) (Quaternion.kt).
Model point (8,-10,8) == rig origin (grip centre); model y = rig +Y (blade), model x = rig X (edges), model z = rig Z.

Usage: handover_s1.py [--solve] [--write]   (--solve: display that reproduces the rig's final key exactly;
       --write: write the solved thirdperson_right/left into the shipped model json)."""
import json, sys
import numpy as np
from pathlib import Path
ROOT = Path(__file__).resolve().parents[4]
MODEL = ROOT/'content/models/items/bastion-greatsword.model.json'
RIG = ROOT/'content/vfx/bastion-s1-rig.yaml'
d2r = np.radians
def Rx(a):
    c, s = np.cos(d2r(a)), np.sin(d2r(a)); return np.array([[1,0,0],[0,c,-s],[0,s,c]])
def Ry(a):
    c, s = np.cos(d2r(a)), np.sin(d2r(a)); return np.array([[c,0,s],[0,1,0],[-s,0,c]])
def Rz(a):
    c, s = np.cos(d2r(a)), np.sin(d2r(a)); return np.array([[c,-s,0],[s,c,0],[0,0,1]])
def M(R=np.eye(3), t=(0,0,0)):
    m = np.eye(4); m[:3,:3] = R; m[:3,3] = t; return m
def S(*s):
    return np.diag([*s, 1.0])
def euler(y, p, r):
    return Ry(-y)@Rx(p)@Rz(r)

def hand_chain(left=False, slim=True, bob_z=0.05, bob_x=0.0):
    """4x4 from item-layer space (before ItemTransform) to world (feet origin, x=left)."""
    sign = -1 if left else 1
    arm_x = (5 if left else -5) + (0.5*sign if slim else 0)   # slim: right x += 0.5, left x -= 0.5
    xrot = np.degrees(-np.pi/10 + bob_x)
    zrot = np.degrees(bob_z) * (1 if not left else -1)
    m = M(Ry(180)) @ S(-1,-1,1) @ S(0.9375,0.9375,0.9375) @ M(t=(0,-1.501,0))
    m = m @ M(Rz(zrot)@Ry(0)@Rx(xrot), (arm_x/16, 2/16, 0))
    m = m @ M(Rx(-90)) @ M(Ry(180)) @ M(t=(sign/16, 2/16, -10/16))
    return m

def display_mat(d, left=False):
    r = list(d['rotation']); t = list(d['translation'])
    if left: r[1] = -r[1]; r[2] = -r[2]; t[0] = -t[0]
    return M(Rx(r[0])@Ry(r[1])@Rz(r[2]), np.array(t)/16) @ S(*d['scale']) @ M(t=(-.5,-.5,-.5)) @ S(1/16,1/16,1/16)

def rig_final():
    import yaml
    rig = yaml.safe_load(RIG.read_text())['rigs'][0]
    key = rig['swing'][-1]['rotate']
    R = euler(*rig['tilt']) @ euler(*key) @ euler(*rig['rest'])
    return np.array(rig['pivot'], float), R, rig

GRIP = np.array([8, -10, 8.])     # model point == rig origin
def model_to_rig(v):              # model units -> rig local blocks
    return (np.asarray(v) - GRIP)/16

def report(name, world_of_model, model):
    lo = np.min([e['from'] for e in model['elements']], 0); hi = np.max([e['to'] for e in model['elements']], 0)
    grip = world_of_model(GRIP); tip = world_of_model([8, hi[1], 8]); pom = world_of_model([8, lo[1], 8])
    axis = tip - grip
    print(f"{name:10s} grip {np.round(grip,3)} tip {np.round(tip,3)} pommel {np.round(pom,3)} len(pommel->tip) {np.linalg.norm(tip-pom):.3f}")
    return grip, tip, axis/np.linalg.norm(axis)

def main():
    model = json.loads(MODEL.read_text())
    P, R, rig = rig_final()
    rig_w = lambda v: P + R@model_to_rig(v)
    g_r, t_r, a_r = report('rig k52', rig_w, model)
    out = {}
    for side, left in (('right', False), ('left', True)):
        A = hand_chain(left)
        d = model['display'][f'thirdperson_{side}hand']
        held = lambda v: (A@display_mat(d, left)@np.append(np.asarray(v, float), 1))[:3]
        g, t, a = report(f'held {side}', held, model)
        hand = (A@np.array([0, 0, 0, 1.]))[:3]
        print(f"   item-layer origin (hand) {np.round(hand,3)}  angle vs rig {np.degrees(np.arccos(np.clip(a@a_r,-1,1))):.1f} deg  grip offset {np.round(g-g_r,3)}")
        out[side] = A
    if '--solve' in sys.argv:
        # Right hand: solve linear part exactly, s = 1/0.9375 (A carries 0.9375 and a proper rotation).
        A = out['right']; Al = A[:3,:3]/0.9375
        for flip in (np.eye(3), Ry(180)):
            Rd = Al.T@R@flip
            s = 1/0.9375
            # world = A ( T + Rd s (v/16 - 0.5) ) ; want at v=GRIP -> P
            Ainv = np.linalg.inv(A)
            T = (Ainv@np.append(P,1))[:3] - Rd@(s*(GRIP/16-0.5))
            # Euler XYZ from Rd = Rx Ry Rz
            yb = np.degrees(np.arcsin(np.clip(Rd[0,2],-1,1)))
            xa = np.degrees(np.arctan2(-Rd[1,2], Rd[2,2])); zc = np.degrees(np.arctan2(-Rd[0,1], Rd[0,0]))
            print('solve flip', 'I' if flip is not None and np.allclose(flip,np.eye(3)) else 'Ry180', 'rot', np.round([xa,yb,zc],2), 'T(px)', np.round(T*16,3), 'scale', round(s,4))
    if '--write' in sys.argv:
        # Hand-anchored: rotation and length = the rig's final key, grip centre stays in the fist
        # (item-layer origin). Flip Ry180 is the solution nearest the accepted [35,-90,0] (3 deg vs 178).
        A = out['right']; Al = A[:3,:3]/0.9375; s = round(1/0.9375, 4)
        Rd = Al.T@R@Ry(180)
        yb = np.degrees(np.arcsin(np.clip(Rd[0,2],-1,1)))
        xa = np.degrees(np.arctan2(-Rd[1,2], Rd[2,2])); zc = np.degrees(np.arctan2(-Rd[0,1], Rd[0,0]))
        rot = [round(float(v), 2) for v in (xa, yb, zc)]
        Rr = Rx(rot[0])@Ry(rot[1])@Rz(rot[2])
        T = -Rr@(s*(GRIP/16-0.5))*16
        disp = {'rotation': rot, 'translation': [round(float(v), 3) for v in T], 'scale': [s, s, s]}
        assert all(abs(v) <= 80 for v in disp['translation']) and s <= 4
        model['display']['thirdperson_righthand'] = disp
        model['display']['thirdperson_lefthand'] = json.loads(json.dumps(disp))
        MODEL.write_text(json.dumps(model, indent=2)+'\n')
        print('wrote', json.dumps(disp))
    return model, P, R

if __name__ == '__main__':
    main()
