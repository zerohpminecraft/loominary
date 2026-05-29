/**
 * SchematicViewer3D — WebGL2 3D preview of carpet platform schematics.
 *
 * Two data sources:
 *   Composition (default) — renders from comp.frames using MC_PALETTE for exact colors.
 *   .litematic file drop  — parses gzip/NBT, extracts carpet block positions & colors.
 *
 * Rendering: one thin slab (1×0.125×1) per carpet block, instanced.
 * Camera: orbit (drag), zoom (scroll), pan (shift-drag or two-finger).
 */

import { h }                            from 'preact';
import { useRef, useEffect, useCallback, useState } from 'preact/hooks';
import type { CompositionState }         from './payload-state.js';
import { MAP_BYTE_TO_NIBBLE }            from './carpet.js';

// ─── Carpet block names (DyeColor order, matches carpet.ts NIBBLE_TO_MAP_BYTE) ──

const CARPET_NAMES = [
  'white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
  'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black',
] as const;

// ─── Carpet color lookup (for composition rendering + .litematic parsing) ────

const CARPET_COLORS: Record<string, [number, number, number]> = {
  'minecraft:white_carpet':        [0.90, 0.90, 0.90],
  'minecraft:orange_carpet':       [0.84, 0.49, 0.19],
  'minecraft:magenta_carpet':      [0.69, 0.30, 0.84],
  'minecraft:light_blue_carpet':   [0.40, 0.60, 0.84],
  'minecraft:yellow_carpet':       [0.88, 0.88, 0.20],
  'minecraft:lime_carpet':         [0.49, 0.79, 0.09],
  'minecraft:pink_carpet':         [0.95, 0.49, 0.64],
  'minecraft:gray_carpet':         [0.30, 0.30, 0.30],
  'minecraft:light_gray_carpet':   [0.60, 0.60, 0.60],
  'minecraft:cyan_carpet':         [0.30, 0.50, 0.60],
  'minecraft:purple_carpet':       [0.49, 0.25, 0.69],
  'minecraft:blue_carpet':         [0.20, 0.30, 0.69],
  'minecraft:brown_carpet':        [0.40, 0.30, 0.20],
  'minecraft:green_carpet':        [0.40, 0.49, 0.20],
  'minecraft:red_carpet':          [0.60, 0.20, 0.20],
  'minecraft:black_carpet':        [0.09, 0.09, 0.09],
  // Shade-channel terrain blocks
  'minecraft:snow_block':          [0.90, 0.92, 0.92],
  'minecraft:grass_block':         [0.40, 0.65, 0.20],
  'minecraft:stone':               [0.50, 0.50, 0.52],
};

// ─── Matrix math (column-major, WebGL convention) ────────────────────────────

type M4 = Float32Array;

function perspective(fovY: number, aspect: number, near: number, far: number): M4 {
  const f  = 1.0 / Math.tan(fovY * 0.5);
  const nf = 1.0 / (near - far);
  return new Float32Array([
    f / aspect, 0, 0, 0,
    0, f, 0, 0,
    0, 0, (far + near) * nf, -1,
    0, 0, 2 * far * near * nf, 0,
  ]);
}

function lookAt(
  eye:    [number, number, number],
  center: [number, number, number],
  up:     [number, number, number],
): M4 {
  let [fx, fy, fz] = [center[0]-eye[0], center[1]-eye[1], center[2]-eye[2]];
  const fl = Math.hypot(fx, fy, fz);
  fx /= fl; fy /= fl; fz /= fl;
  let [sx, sy, sz] = [fy*up[2]-fz*up[1], fz*up[0]-fx*up[2], fx*up[1]-fy*up[0]];
  const sl = Math.hypot(sx, sy, sz);
  sx /= sl; sy /= sl; sz /= sl;
  const [ux, uy, uz] = [sy*fz-sz*fy, sz*fx-sx*fz, sx*fy-sy*fx];
  const [ex, ey, ez] = eye;
  return new Float32Array([
    sx, ux, -fx, 0,
    sy, uy, -fy, 0,
    sz, uz, -fz, 0,
    -(sx*ex+sy*ey+sz*ez), -(ux*ex+uy*ey+uz*ez), fx*ex+fy*ey+fz*ez, 1,
  ]);
}

function mul(a: M4, b: M4): M4 {
  const o = new Float32Array(16);
  for (let i = 0; i < 4; i++)
    for (let j = 0; j < 4; j++) {
      let s = 0;
      for (let k = 0; k < 4; k++) s += a[k*4+i] * b[j*4+k];
      o[j*4+i] = s;
    }
  return o;
}

// ─── Carpet slab geometry (1.0 × 0.125 × 1.0) ───────────────────────────────

function makeSlab(): { pos: Float32Array; nor: Float32Array; idx: Uint16Array } {
  const H = 0.125;
  const faces: Array<{ n: [number,number,number]; v: [number,number,number][] }> = [
    { n:[0, 1,0], v:[[0,H,0],[1,H,0],[1,H,1],[0,H,1]] }, // top
    { n:[0,-1,0], v:[[0,0,1],[1,0,1],[1,0,0],[0,0,0]] }, // bottom
    { n:[0,0,-1], v:[[1,0,0],[0,0,0],[0,H,0],[1,H,0]] }, // −Z
    { n:[0,0, 1], v:[[0,0,1],[1,0,1],[1,H,1],[0,H,1]] }, // +Z
    { n:[-1,0,0], v:[[0,0,0],[0,0,1],[0,H,1],[0,H,0]] }, // −X
    { n:[ 1,0,0], v:[[1,0,1],[1,0,0],[1,H,0],[1,H,1]] }, // +X
  ];
  const pos: number[] = [], nor: number[] = [], idx: number[] = [];
  for (const { n, v } of faces) {
    const base = pos.length / 3;
    for (const [x,y,z] of v) { pos.push(x,y,z); nor.push(...n); }
    idx.push(base, base+1, base+2, base, base+2, base+3);
  }
  return { pos: new Float32Array(pos), nor: new Float32Array(nor), idx: new Uint16Array(idx) };
}

// ─── Shaders ─────────────────────────────────────────────────────────────────

const VERT = `#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNor;
layout(location=2) in vec3 aXYZ;  // per-instance world XYZ offset
layout(location=3) in vec3 aRGB;  // per-instance color

uniform mat4 uVP;

out vec3 vColor;
out float vLight;

void main() {
  gl_Position = uVP * vec4(aXYZ.x + aPos.x, aXYZ.y + aPos.y, aXYZ.z + aPos.z, 1.0);
  vec3 L = normalize(vec3(0.6, 2.0, 0.8));
  vLight = max(dot(normalize(aNor), L), 0.0) * 0.65 + 0.35;
  vColor = aRGB;
}`;

const FRAG = `#version 300 es
precision mediump float;
in vec3 vColor;
in float vLight;
out vec4 fragColor;
void main() { fragColor = vec4(vColor * vLight, 1.0); }`;

// ─── WebGL helpers ────────────────────────────────────────────────────────────

function compileShader(gl: WebGL2RenderingContext, type: number, src: string): WebGLShader {
  const sh = gl.createShader(type)!;
  gl.shaderSource(sh, src);
  gl.compileShader(sh);
  if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS))
    throw new Error(gl.getShaderInfoLog(sh) ?? 'shader error');
  return sh;
}

function createProgram(gl: WebGL2RenderingContext): WebGLProgram {
  const prog = gl.createProgram()!;
  gl.attachShader(prog, compileShader(gl, gl.VERTEX_SHADER, VERT));
  gl.attachShader(prog, compileShader(gl, gl.FRAGMENT_SHADER, FRAG));
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS))
    throw new Error(gl.getProgramInfoLog(prog) ?? 'link error');
  return prog;
}

// ─── Instance data from composition ──────────────────────────────────────────
//
// Renders the physical carpet schematic: 16-colour carpet blocks coloured by
// their actual dye colour, with shade bits (mapByte & 3) driving the Y height
// so the shade-channel staircase is visible.
//
// Instance buffer format: [x, y, z, r, g, b] — stride 6 floats (24 bytes).

const TILE_GAP = 8;

function instancesFromComp(comp: CompositionState): {
  data: Float32Array; count: number;
  cx: number; cz: number; span: number;
} {
  const { gridCols, gridRows, frames, activeFrame } = comp;
  const TILE = 128;
  const buf: number[] = [];

  for (let ti = 0; ti < gridCols * gridRows; ti++) {
    const tc  = ti % gridCols, tr = Math.floor(ti / gridCols);
    const ox  = tc * (TILE + TILE_GAP);
    const oz  = tr * (TILE + TILE_GAP);
    const map = frames[ti]?.[activeFrame] ?? new Uint8Array(TILE * TILE);

    for (let z = 0; z < TILE; z++) {
      for (let x = 0; x < TILE; x++) {
        const mapByte = map[z * TILE + x];
        const nibble  = MAP_BYTE_TO_NIBBLE[mapByte];
        if (nibble === 255) continue;   // non-carpet byte → no block
        const col = CARPET_COLORS[`minecraft:${CARPET_NAMES[nibble]}_carpet`]
                 ?? [0.5, 0.5, 0.5] as [number, number, number];
        const y = mapByte & 3;          // shade bits → physical height (0/1/2)
        buf.push(ox + x, y, oz + z, col[0], col[1], col[2]);
      }
    }
  }

  const totalW = gridCols * (TILE + TILE_GAP) - TILE_GAP;
  const totalD = gridRows * (TILE + TILE_GAP) - TILE_GAP;
  return {
    data: new Float32Array(buf), count: buf.length / 6,
    cx: totalW / 2, cz: totalD / 2,
    span: Math.max(totalW, totalD),
  };
}

// ─── NBT parser (for .litematic file loading) ────────────────────────────────

type NbtVal = number | bigint | string | NbtVal[] | Map<string, NbtVal>;

function parseNbt(buf: ArrayBuffer): Map<string, NbtVal> {
  const v = new DataView(buf);
  let p = 0;
  const u8  = () => v.getUint8(p++);
  const u16 = () => { const n = v.getUint16(p, false); p += 2; return n; };
  const i32 = () => { const n = v.getInt32(p, false);  p += 4; return n; };
  const str = () => { const n = u16(); const b = new Uint8Array(buf, p, n); p += n; return new TextDecoder().decode(b); };

  function read(t: number): NbtVal {
    switch (t) {
      case 1: return u8();
      case 2: { const n=v.getInt16(p,false); p+=2; return n; }
      case 3: return i32();
      case 4: { const hi=v.getInt32(p,false); const lo=v.getUint32(p+4,false); p+=8; return (BigInt(hi)<<32n)|BigInt(lo); }
      case 5: { const n=v.getFloat32(p,false); p+=4; return n; }
      case 6: { const n=v.getFloat64(p,false); p+=8; return n; }
      case 7: { const n=i32(); const a=new Uint8Array(buf,p,n); p+=n; return Array.from(a); }
      case 8: return str();
      case 9: { const et=u8(); const n=i32(); const a:NbtVal[]=[]; for(let i=0;i<n;i++) a.push(read(et)); return a; }
      case 10: {
        const m = new Map<string,NbtVal>();
        while (true) { const t2=u8(); if (!t2) break; m.set(str(), read(t2)); }
        return m;
      }
      case 11: { const n=i32(); const a:number[]=[]; for(let i=0;i<n;i++) a.push(i32()); return a; }
      case 12: {
        const n=i32(); const a:bigint[]=[];
        for(let i=0;i<n;i++){
          const hi=v.getInt32(p,false); const lo=v.getUint32(p+4,false); p+=8;
          a.push((BigInt(hi)<<32n)|BigInt(lo));
        }
        return a;
      }
      default: return 0;
    }
  }

  const rootType = u8(); str(); // root type + name
  return read(rootType) as Map<string, NbtVal>;
}

// ─── Litematica block-state unpacker ─────────────────────────────────────────

function unpackStates(longs: bigint[], count: number, paletteSize: number): number[] {
  const bits    = Math.max(2, Math.ceil(Math.log2(Math.max(paletteSize, 2))));
  const perLong = Math.floor(64 / bits);
  const mask    = (1n << BigInt(bits)) - 1n;
  const out: number[] = [];
  for (const long of longs) {
    for (let s = 0; s < perLong; s++) {
      if (out.length >= count) break;
      out.push(Number((long >> BigInt(s * bits)) & mask));
    }
  }
  return out;
}

// ─── Instance data from a parsed litematic ───────────────────────────────────

function instancesFromLitematic(root: Map<string, NbtVal>): {
  data: Float32Array; count: number;
  cx: number; cz: number; span: number;
} | null {
  const regions = root.get('Regions') as Map<string, NbtVal> | undefined;
  if (!regions) return null;
  const region = [...regions.values()][0] as Map<string, NbtVal> | undefined;
  if (!region) return null;

  const size     = region.get('Size') as Map<string, NbtVal>;
  const sizeX    = Math.abs(size?.get('x') as number ?? 128);
  const sizeY    = Math.abs(size?.get('y') as number ?? 1);
  const sizeZ    = Math.abs(size?.get('z') as number ?? 128);
  const palSrc   = region.get('BlockStatePalette') as NbtVal[] | undefined;
  const bsLongs  = region.get('BlockStates') as bigint[] | undefined;
  if (!palSrc || !bsLongs) return null;

  // Map palette index → RGB (null = transparent/skip)
  const palColors: ([number,number,number] | null)[] = palSrc.map(e => {
    const entry = e as Map<string, NbtVal>;
    const name  = entry.get('Name') as string;
    return CARPET_COLORS[name] ?? null;
  });

  const states = unpackStates(bsLongs, sizeX * sizeY * sizeZ, palSrc.length);
  const buf: number[] = [];
  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;

  // Litematica YZX ordering: idx = (y * sizeZ + z) * sizeX + x
  for (let idx = 0; idx < states.length; idx++) {
    const col = palColors[states[idx]];
    if (!col) continue;
    const x =  idx % sizeX;
    const r = Math.floor(idx / sizeX);
    const z =  r % sizeZ;
    const y = Math.floor(r / sizeZ);
    buf.push(x, y, z, col[0], col[1], col[2]);
    if (x < minX) minX = x; if (x > maxX) maxX = x;
    if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
  }

  if (buf.length === 0) return null;
  return {
    data: new Float32Array(buf), count: buf.length / 6,
    cx: (minX + maxX) / 2, cz: (minZ + maxZ) / 2,
    span: Math.max(maxX - minX, maxZ - minZ),
  };
}

// ─── Component ────────────────────────────────────────────────────────────────

export interface SchematicViewer3DProps {
  comp: CompositionState;
}

export function SchematicViewer3D({ comp }: SchematicViewer3DProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [error,     setError]     = useState<string | null>(null);
  const [dropHover, setDropHover] = useState(false);
  const [sourceLabel, setSourceLabel] = useState<string | null>(null);

  // All WebGL + camera state lives in a ref to avoid triggering re-renders
  // inside the rAF loop.
  const wgl = useRef({
    gl:          null as WebGL2RenderingContext | null,
    prog:        null as WebGLProgram | null,
    vao:         null as WebGLVertexArrayObject | null,
    instBuf:     null as WebGLBuffer | null,
    uVP:         null as WebGLUniformLocation | null,
    indexCount:  36,
    instCount:   0,
    // camera (spherical coords around target)
    az:  Math.PI / 2,  // azimuth: south of scene → looking north (north-up)
    el:  0.75,  // elevation (rad)
    r:   200,   // radius
    tx:  64, ty: 0, tz: 64,  // look-at target
    // mouse
    down: false, px: 0, py: 0, shift: false,
    raf: 0,
  });

  // Upload instance data (positions + colors) to the GPU.
  const uploadInstances = useCallback((
    data: Float32Array, count: number,
    cx: number, cz: number, span: number,
  ) => {
    const s = wgl.current;
    if (!s.gl || !s.instBuf) return;
    s.gl.bindBuffer(s.gl.ARRAY_BUFFER, s.instBuf);
    s.gl.bufferData(s.gl.ARRAY_BUFFER, data, s.gl.DYNAMIC_DRAW);
    s.gl.bindBuffer(s.gl.ARRAY_BUFFER, null);
    s.instCount = count;
    s.tx = cx; s.ty = 0; s.tz = cz;
    s.r  = Math.max(span * 0.75, 50);
  }, []);

  // ── WebGL initialisation ──────────────────────────────────────────────────
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const gl = canvas.getContext('webgl2');
    if (!gl) { setError('WebGL2 is not supported by this browser.'); return; }

    const s = wgl.current;
    s.gl = gl;

    try { s.prog = createProgram(gl); }
    catch (e) { setError(String(e)); return; }

    s.uVP = gl.getUniformLocation(s.prog, 'uVP');

    // Geometry buffers
    const { pos, nor, idx } = makeSlab();
    s.vao = gl.createVertexArray()!;
    gl.bindVertexArray(s.vao);

    const posBuf = gl.createBuffer()!;
    gl.bindBuffer(gl.ARRAY_BUFFER, posBuf);
    gl.bufferData(gl.ARRAY_BUFFER, pos, gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 0, 0);

    const norBuf = gl.createBuffer()!;
    gl.bindBuffer(gl.ARRAY_BUFFER, norBuf);
    gl.bufferData(gl.ARRAY_BUFFER, nor, gl.STATIC_DRAW);
    gl.enableVertexAttribArray(1);
    gl.vertexAttribPointer(1, 3, gl.FLOAT, false, 0, 0);

    const idxBuf = gl.createBuffer()!;
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, idxBuf);
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, idx, gl.STATIC_DRAW);

    // Instance buffer: stride = 6 × 4 = 24 bytes (XYZ then RGB)
    s.instBuf = gl.createBuffer()!;
    gl.bindBuffer(gl.ARRAY_BUFFER, s.instBuf);
    gl.enableVertexAttribArray(2);
    gl.vertexAttribPointer(2, 3, gl.FLOAT, false, 24, 0);  // aXYZ at offset 0
    gl.vertexAttribDivisor(2, 1);
    gl.enableVertexAttribArray(3);
    gl.vertexAttribPointer(3, 3, gl.FLOAT, false, 24, 12); // aRGB at offset 12
    gl.vertexAttribDivisor(3, 1);

    gl.bindVertexArray(null);

    gl.clearColor(0.08, 0.08, 0.13, 1);
    gl.enable(gl.DEPTH_TEST);
    gl.enable(gl.CULL_FACE);

    // Render loop
    const render = () => {
      const { az, el, r, tx, ty, tz, prog, vao, uVP, instCount, indexCount } = s;

      const ex = tx + r * Math.cos(az) * Math.sin(el);
      const ey = ty + r * Math.cos(el) + 4;
      const ez = tz + r * Math.sin(az) * Math.sin(el);

      const view = lookAt([ex, ey, ez], [tx, ty + 1, tz], [0, 1, 0]);

      const dpr = window.devicePixelRatio || 1;
      const cw  = Math.round(canvas.clientWidth  * dpr);
      const ch  = Math.round(canvas.clientHeight * dpr);
      if (canvas.width !== cw || canvas.height !== ch) {
        canvas.width = cw; canvas.height = ch;
      }
      gl.viewport(0, 0, cw, ch);

      const proj = perspective(Math.PI / 4, cw / Math.max(ch, 1), 0.5, 4000);
      const vp   = mul(proj, view);

      gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);

      if (prog && vao && instCount > 0) {
        gl.useProgram(prog);
        gl.bindVertexArray(vao);
        gl.uniformMatrix4fv(uVP, false, vp);
        gl.drawElementsInstanced(gl.TRIANGLES, indexCount, gl.UNSIGNED_SHORT, 0, instCount);
        gl.bindVertexArray(null);
      }

      s.raf = requestAnimationFrame(render);
    };
    s.raf = requestAnimationFrame(render);

    const ro = new ResizeObserver(() => { /* resize handled in loop */ });
    ro.observe(canvas);

    return () => {
      cancelAnimationFrame(s.raf);
      ro.disconnect();
      s.gl = null;
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Refresh instances when composition changes ────────────────────────────
  useEffect(() => {
    if (!wgl.current.gl) return;
    const { data, count, cx, cz, span } = instancesFromComp(comp);
    uploadInstances(data, count, cx, cz, span);
    setSourceLabel(null);
  }, [comp, uploadInstances]);

  // ── Mouse / pointer controls ──────────────────────────────────────────────

  const onDown = useCallback((e: PointerEvent) => {
    const s = wgl.current;
    s.down = true; s.px = e.clientX; s.py = e.clientY;
    s.shift = e.shiftKey;
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  }, []);

  const onMove = useCallback((e: PointerEvent) => {
    const s = wgl.current;
    if (!s.down) return;
    const dx = e.clientX - s.px, dy = e.clientY - s.py;
    s.px = e.clientX; s.py = e.clientY;

    if (s.shift || e.buttons === 4) {
      // Pan: move target in the XZ plane
      const scale = s.r * 0.002;
      const sinAz = Math.sin(s.az), cosAz = Math.cos(s.az);
      s.tx += (-cosAz * dx - sinAz * dy * 0.3) * scale;
      s.tz += ( sinAz * dx - cosAz * dy * 0.3) * scale;
    } else {
      // Orbit
      s.az += dx * 0.008;
      s.el  = Math.max(0.08, Math.min(Math.PI * 0.92, s.el - dy * 0.008));
    }
  }, []);

  const onUp   = useCallback(() => { wgl.current.down = false; }, []);

  const onWheel = useCallback((e: WheelEvent) => {
    e.preventDefault();
    wgl.current.r = Math.max(8, Math.min(3000, wgl.current.r * (e.deltaY > 0 ? 1.12 : 0.89)));
  }, []);

  // ── .litematic file loading ───────────────────────────────────────────────

  const loadFile = useCallback(async (file: File) => {
    if (!file.name.endsWith('.litematic') && !file.name.endsWith('.nbt')) {
      setError('Drop a .litematic file to preview it.');
      return;
    }
    try {
      const { inflate } = await import('pako');
      const compressed  = new Uint8Array(await file.arrayBuffer());
      const decompressed = inflate(compressed);
      const root = parseNbt(decompressed.buffer as ArrayBuffer);
      const result = instancesFromLitematic(root);
      if (!result) { setError('No carpet blocks found in this schematic.'); return; }
      uploadInstances(result.data, result.count, result.cx, result.cz, result.span);
      setSourceLabel(file.name);
      setError(null);
    } catch (e) {
      setError(`Failed to parse schematic: ${e}`);
    }
  }, [uploadInstances]);

  const onDragOver = useCallback((e: DragEvent) => {
    e.preventDefault(); setDropHover(true);
  }, []);

  const onDragLeave = useCallback(() => setDropHover(false), []);

  const onDrop = useCallback((e: DragEvent) => {
    e.preventDefault(); setDropHover(false);
    const file = e.dataTransfer?.files[0];
    if (file) void loadFile(file);
  }, [loadFile]);

  // ─── Render ───────────────────────────────────────────────────────────────

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%', background: '#111' }}>

      <canvas
        ref={canvasRef}
        style={{ display: 'block', width: '100%', height: '100%',
          cursor: 'grab', outline: dropHover ? '2px solid #5af' : 'none' }}
        onPointerDown={onDown  as unknown as h.JSX.MouseEventHandler<HTMLCanvasElement>}
        onPointerMove={onMove  as unknown as h.JSX.MouseEventHandler<HTMLCanvasElement>}
        onPointerUp={onUp      as unknown as h.JSX.MouseEventHandler<HTMLCanvasElement>}
        onDragOver={onDragOver as unknown as h.JSX.DragEventHandler<HTMLCanvasElement>}
        onDragLeave={onDragLeave}
        onDrop={onDrop         as unknown as h.JSX.DragEventHandler<HTMLCanvasElement>}
      />

      {/* Wheel listener added imperatively (needs passive:false) */}
      <WheelListener canvas={canvasRef} onWheel={onWheel} />

      {/* Drop target overlay */}
      <div style={{
        position: 'absolute', inset: 0, pointerEvents: 'none',
        border: dropHover ? '2px solid #5af' : '2px solid transparent',
        transition: 'border-color 0.15s',
      }} />

      {/* Source label */}
      <div style={{
        position: 'absolute', bottom: 8, left: 10,
        fontSize: '0.6em', color: '#666', pointerEvents: 'none',
      }}>
        {sourceLabel
          ? `📄 ${sourceLabel}`
          : `Carpet schematic · ${comp.gridCols}×${comp.gridRows} tile${comp.gridCols*comp.gridRows>1?'s':''}`}
        {' · '}drag a .litematic to preview it
      </div>

      {/* Controls hint */}
      <div style={{
        position: 'absolute', bottom: 8, right: 10,
        fontSize: '0.6em', color: '#555', pointerEvents: 'none',
      }}>
        drag=orbit · shift+drag=pan · scroll=zoom
      </div>

      {/* Error banner */}
      {error && (
        <div style={{
          position: 'absolute', top: 8, left: 8, right: 8,
          background: '#2a1212', border: '1px solid #f55', borderRadius: 4,
          color: '#f77', fontSize: '0.6em', padding: '6px 10px',
        }}>
          {error}
          <button onClick={() => setError(null)} style={{
            float: 'right', background: 'none', border: 'none',
            color: '#f55', cursor: 'pointer', fontSize: '1em',
          }}>✕</button>
        </div>
      )}

      {/* WebGL unavailable fallback */}
      {error?.includes('not supported') && (
        <div style={{
          position: 'absolute', inset: 0,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: '#555', fontSize: '0.7em',
        }}>
          3D preview requires WebGL2 (Chrome/Edge/Firefox/Safari 15+)
        </div>
      )}
    </div>
  );
}

// ─── WheelListener ────────────────────────────────────────────────────────────
// Adds a non-passive wheel listener that can call preventDefault().

function WheelListener({ canvas, onWheel }: {
  canvas: { current: HTMLCanvasElement | null };
  onWheel: (e: WheelEvent) => void;
}) {
  useEffect(() => {
    const el = canvas.current;
    if (!el) return;
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [canvas, onWheel]);
  return null;
}
