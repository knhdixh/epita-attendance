// Water cursor trail, copied verbatim from the knhdixh cookbook template (§5). Loaded only on the signed-out page.
/* ---------- fluid ---------- */
(function () {
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  var canvas = document.getElementById("fluid");
  var gl = canvas.getContext("webgl2", { alpha: true, premultipliedAlpha: true, antialias: false, depth: false, stencil: false });
  if (!gl || !gl.getExtension("EXT_color_buffer_float")) { canvas.remove(); return; }
  gl.getExtension("OES_texture_float_linear");

  var CFG = {
    SIM: 128, DYE: 768,
    VEL_DISSIPATION: 1.4,
    DYE_DISSIPATION: 2.1,
    PRESSURE_ITER: 20,
    CURL: 14,
    RADIUS: 0.13,
    FORCE: 5200,
    INK: 0.24
  };

  var baseVS = `#version 300 es
    precision highp float;
    in vec2 aPos;
    out vec2 vUv, vL, vR, vT, vB;
    uniform vec2 texel;
    void main () {
      vUv = aPos * .5 + .5;
      vL = vUv - vec2(texel.x, 0.); vR = vUv + vec2(texel.x, 0.);
      vT = vUv + vec2(0., texel.y); vB = vUv - vec2(0., texel.y);
      gl_Position = vec4(aPos, 0., 1.);
    }`;

  var FS = {
    splat: `#version 300 es
      precision highp float;
      in vec2 vUv; out vec4 o;
      uniform sampler2D target; uniform float aspect, radius; uniform vec3 color; uniform vec2 point;
      void main () {
        vec2 p = vUv - point; p.x *= aspect;
        vec3 s = exp(-dot(p, p) / radius) * color;
        o = vec4(texture(target, vUv).xyz + s, 1.);
      }`,
    advect: `#version 300 es
      precision highp float;
      in vec2 vUv; out vec4 o;
      uniform sampler2D velocity, source; uniform vec2 texel; uniform float dt, dissipation;
      void main () {
        vec2 c = vUv - dt * texture(velocity, vUv).xy * texel;
        o = texture(source, c) / (1. + dissipation * dt);
      }`,
    divergence: `#version 300 es
      precision highp float;
      in vec2 vUv, vL, vR, vT, vB; out vec4 o;
      uniform sampler2D velocity;
      void main () {
        float L = texture(velocity, vL).x, R = texture(velocity, vR).x;
        float T = texture(velocity, vT).y, B = texture(velocity, vB).y;
        vec2 C = texture(velocity, vUv).xy;
        if (vL.x < 0.) L = -C.x; if (vR.x > 1.) R = -C.x;
        if (vT.y > 1.) T = -C.y; if (vB.y < 0.) B = -C.y;
        o = vec4(.5 * (R - L + T - B), 0., 0., 1.);
      }`,
    curl: `#version 300 es
      precision highp float;
      in vec2 vUv, vL, vR, vT, vB; out vec4 o;
      uniform sampler2D velocity;
      void main () {
        float L = texture(velocity, vL).y, R = texture(velocity, vR).y;
        float T = texture(velocity, vT).x, B = texture(velocity, vB).x;
        o = vec4(.5 * (R - L - T + B), 0., 0., 1.);
      }`,
    vorticity: `#version 300 es
      precision highp float;
      in vec2 vUv, vL, vR, vT, vB; out vec4 o;
      uniform sampler2D velocity, curl; uniform float strength, dt;
      void main () {
        float L = texture(curl, vL).x, R = texture(curl, vR).x;
        float T = texture(curl, vT).x, B = texture(curl, vB).x, C = texture(curl, vUv).x;
        vec2 f = .5 * vec2(abs(T) - abs(B), abs(R) - abs(L));
        f /= length(f) + .0001; f *= strength * C; f.y *= -1.;
        vec2 v = texture(velocity, vUv).xy + f * dt;
        o = vec4(clamp(v, -1000., 1000.), 0., 1.);
      }`,
    pressure: `#version 300 es
      precision highp float;
      in vec2 vUv, vL, vR, vT, vB; out vec4 o;
      uniform sampler2D pressure, divergence;
      void main () {
        float L = texture(pressure, vL).x, R = texture(pressure, vR).x;
        float T = texture(pressure, vT).x, B = texture(pressure, vB).x;
        o = vec4((L + R + B + T - texture(divergence, vUv).x) * .25, 0., 0., 1.);
      }`,
    gradient: `#version 300 es
      precision highp float;
      in vec2 vUv, vL, vR, vT, vB; out vec4 o;
      uniform sampler2D pressure, velocity;
      void main () {
        float L = texture(pressure, vL).x, R = texture(pressure, vR).x;
        float T = texture(pressure, vT).x, B = texture(pressure, vB).x;
        vec2 v = texture(velocity, vUv).xy - vec2(R - L, T - B);
        o = vec4(v, 0., 1.);
      }`,
    scale: `#version 300 es
      precision highp float;
      in vec2 vUv; out vec4 o;
      uniform sampler2D tex; uniform float value;
      void main () { o = value * texture(tex, vUv); }`,
    display: `#version 300 es
      precision highp float;
      in vec2 vUv, vL, vR, vT, vB; out vec4 o;
      uniform sampler2D dye;
      void main () {
        float d = texture(dye, vUv).x;
        // Height-field shading so the ink reads like water, not paint.
        float dx = texture(dye, vR).x - texture(dye, vL).x;
        float dy = texture(dye, vT).x - texture(dye, vB).x;
        vec3 n = normalize(vec3(dx, dy, .06));
        float light = clamp(dot(n, normalize(vec3(-.4, .6, 1.))), 0., 1.);
        float a = 1. - exp(-d * 2.2);
        a = clamp(a, 0., .55);
        vec3 deep = vec3(.05, .32, 1.);
        vec3 shallow = vec3(.45, .72, 1.);
        vec3 col = mix(shallow, deep, smoothstep(0., .5, a));
        col = mix(col * .85, vec3(1.), pow(light, 18.) * .9);
        o = vec4(col * a, a);
      }`
  };

  function compile(type, src) {
    var s = gl.createShader(type);
    gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
    return s;
  }
  var vs = compile(gl.VERTEX_SHADER, baseVS);
  function program(src) {
    var p = gl.createProgram();
    gl.attachShader(p, vs);
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, src));
    gl.bindAttribLocation(p, 0, "aPos");
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    var u = {}, n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
    for (var i = 0; i < n; i++) {
      var name = gl.getActiveUniform(p, i).name;
      u[name] = gl.getUniformLocation(p, name);
    }
    return { p: p, u: u };
  }

  var P;
  try {
    P = {};
    for (var k in FS) P[k] = program(FS[k]);
  } catch (e) { canvas.remove(); return; }

  gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, -1, 1, 1, 1, 1, -1]), gl.STATIC_DRAW);
  gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, gl.createBuffer());
  gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array([0, 1, 2, 0, 2, 3]), gl.STATIC_DRAW);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.enableVertexAttribArray(0);

  function fbo(w, h, internal, format) {
    var tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage2D(gl.TEXTURE_2D, 0, internal, w, h, 0, format, gl.HALF_FLOAT, null);
    var fb = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    gl.viewport(0, 0, w, h);
    gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT);
    return { tex: tex, fb: fb, w: w, h: h, tx: 1 / w, ty: 1 / h,
      attach: function (unit) { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex); return unit; } };
  }
  function double(w, h, i, f) {
    var a = fbo(w, h, i, f), b = fbo(w, h, i, f);
    return { get read() { return a; }, get write() { return b; }, swap: function () { var t = a; a = b; b = t; }, w: w, h: h, tx: a.tx, ty: a.ty };
  }
  function res(r) {
    var ar = gl.drawingBufferWidth / gl.drawingBufferHeight;
    if (ar < 1) ar = 1 / ar;
    var min = Math.round(r), max = Math.round(r * ar);
    return gl.drawingBufferWidth > gl.drawingBufferHeight ? { w: max, h: min } : { w: min, h: max };
  }

  var velocity, dye, divergence, curl, pressure;
  function initFBOs() {
    var s = res(CFG.SIM), d = res(CFG.DYE);
    velocity = double(s.w, s.h, gl.RG16F, gl.RG);
    dye = double(d.w, d.h, gl.R16F, gl.RED);
    divergence = fbo(s.w, s.h, gl.R16F, gl.RED);
    curl = fbo(s.w, s.h, gl.R16F, gl.RED);
    pressure = double(s.w, s.h, gl.R16F, gl.RED);
  }

  function resize() {
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    var w = Math.floor(canvas.clientWidth * dpr), h = Math.floor(canvas.clientHeight * dpr);
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; initFBOs(); }
  }
  resize();
  addEventListener("resize", resize);

  function blit(target) {
    if (target) { gl.viewport(0, 0, target.w, target.h); gl.bindFramebuffer(gl.FRAMEBUFFER, target.fb); }
    else { gl.viewport(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight); gl.bindFramebuffer(gl.FRAMEBUFFER, null); }
    gl.drawElements(gl.TRIANGLES, 6, gl.UNSIGNED_SHORT, 0);
  }
  function use(prog, tx, ty) { gl.useProgram(prog.p); if (prog.u.texel) gl.uniform2f(prog.u.texel, tx, ty); return prog.u; }

  function splat(x, y, dx, dy, ink) {
    var u = use(P.splat, velocity.tx, velocity.ty);
    gl.uniform1i(u.target, velocity.read.attach(0));
    gl.uniform1f(u.aspect, canvas.width / canvas.height);
    gl.uniform2f(u.point, x, y);
    gl.uniform3f(u.color, dx, dy, 0);
    gl.uniform1f(u.radius, CFG.RADIUS / 100 * Math.max(1, canvas.width / canvas.height));
    blit(velocity.write); velocity.swap();

    gl.uniform1i(u.target, dye.read.attach(0));
    gl.uniform3f(u.color, ink, 0, 0);
    blit(dye.write); dye.swap();
  }

  function step(dt) {
    gl.disable(gl.BLEND);
    var u;
    u = use(P.curl, velocity.tx, velocity.ty);
    gl.uniform1i(u.velocity, velocity.read.attach(0)); blit(curl);

    u = use(P.vorticity, velocity.tx, velocity.ty);
    gl.uniform1i(u.velocity, velocity.read.attach(0)); gl.uniform1i(u.curl, curl.attach(1));
    gl.uniform1f(u.strength, CFG.CURL); gl.uniform1f(u.dt, dt);
    blit(velocity.write); velocity.swap();

    u = use(P.divergence, velocity.tx, velocity.ty);
    gl.uniform1i(u.velocity, velocity.read.attach(0)); blit(divergence);

    u = use(P.scale, velocity.tx, velocity.ty);
    gl.uniform1i(u.tex, pressure.read.attach(0)); gl.uniform1f(u.value, .8);
    blit(pressure.write); pressure.swap();

    u = use(P.pressure, velocity.tx, velocity.ty);
    gl.uniform1i(u.divergence, divergence.attach(0));
    for (var i = 0; i < CFG.PRESSURE_ITER; i++) {
      gl.uniform1i(u.pressure, pressure.read.attach(1));
      blit(pressure.write); pressure.swap();
    }

    u = use(P.gradient, velocity.tx, velocity.ty);
    gl.uniform1i(u.pressure, pressure.read.attach(0)); gl.uniform1i(u.velocity, velocity.read.attach(1));
    blit(velocity.write); velocity.swap();

    u = use(P.advect, velocity.tx, velocity.ty);
    gl.uniform1i(u.velocity, velocity.read.attach(0)); gl.uniform1i(u.source, velocity.read.attach(0));
    gl.uniform1f(u.dt, dt); gl.uniform1f(u.dissipation, CFG.VEL_DISSIPATION);
    blit(velocity.write); velocity.swap();

    u = use(P.advect, dye.tx, dye.ty);
    gl.uniform2f(u.texel, velocity.tx, velocity.ty);
    gl.uniform1i(u.velocity, velocity.read.attach(0)); gl.uniform1i(u.source, dye.read.attach(1));
    gl.uniform1f(u.dissipation, CFG.DYE_DISSIPATION);
    blit(dye.write); dye.swap();
  }

  function render() {
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    gl.viewport(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
    var u = use(P.display, dye.tx, dye.ty);
    gl.uniform1i(u.dye, dye.read.attach(0));
    gl.drawElements(gl.TRIANGLES, 6, gl.UNSIGNED_SHORT, 0);
  }

  // Pointer → splats. Coalesce moves per frame so fast drags stay smooth.
  var pointer = { x: 0, y: 0, px: 0, py: 0, moved: false, seen: false };
  function onMove(cx, cy) {
    var x = cx / innerWidth, y = 1 - cy / innerHeight;
    if (!pointer.seen) { pointer.px = x; pointer.py = y; pointer.seen = true; }
    pointer.x = x; pointer.y = y; pointer.moved = true;
  }
  addEventListener("pointermove", function (e) { onMove(e.clientX, e.clientY); }, { passive: true });
  addEventListener("touchmove", function (e) { var t = e.touches[0]; onMove(t.clientX, t.clientY); }, { passive: true });
  addEventListener("pointerleave", function () { pointer.seen = false; });

  // Scrolling stirs the water too.
  var lastScroll = scrollY;
  addEventListener("scroll", function () {
    var d = scrollY - lastScroll; lastScroll = scrollY;
    if (pointer.seen) splat(pointer.x, pointer.y, 0, d * 4, CFG.INK * .5);
  }, { passive: true });

  var last = performance.now(), idle = 0;
  function loop(now) {
    var dt = Math.min((now - last) / 1000, 1 / 30); last = now;
    resize();
    if (pointer.moved) {
      var dx = pointer.x - pointer.px, dy = pointer.y - pointer.py;
      var aspect = canvas.width / canvas.height;
      if (aspect < 1) dx *= aspect; else dy /= aspect;
      var speed = Math.min(Math.hypot(dx, dy) * 40, 1);
      splat(pointer.x, pointer.y, dx * CFG.FORCE, dy * CFG.FORCE, CFG.INK * (.35 + speed));
      pointer.px = pointer.x; pointer.py = pointer.y; pointer.moved = false;
      idle = 0;
    } else idle += dt;
    // Keep simulating for a few seconds after the last move, then rest the GPU.
    if (idle < 6) { step(dt); render(); }
    requestAnimationFrame(loop);
  }
  requestAnimationFrame(loop);

  document.addEventListener("visibilitychange", function () { last = performance.now(); });
})();
