/* Panel de administración Peaje IoT.
 * Toda la información se inserta con textContent (nunca innerHTML): un nombre o patente con código
 * malicioso guardado en la base se muestra como texto y no se ejecuta.
 * El token JWT se guarda en sessionStorage: se borra al cerrar la pestaña. */
"use strict";

const CLAVE_TOKEN = "peaje_admin_token";
const $ = (id) => document.getElementById(id);

const ESTADOS = {
  APROBADO: ["Aprobado", "bien"],
  LECTURA_DUPLICADA: ["Lectura repetida (sin cobro)", "neutro"],
  SALDO_INSUFICIENTE: ["Saldo insuficiente", "critico"],
  USUARIO_NO_ENCONTRADO: ["Tarjeta no registrada", "critico"],
  TRANSACCION_INVALIDA: ["Lectura inválida", "critico"],
  CREDENCIAL_INACTIVA: ["Tarjeta bloqueada", "critico"],
  METODO_DESHABILITADO: ["Método deshabilitado", "critico"],
  TOKEN_INVALIDO: ["Token inválido", "critico"],
  TOKEN_EXPIRADO: ["Token expirado", "critico"],
  TOKEN_REUTILIZADO: ["Token reutilizado", "critico"],
  TARIFA_NO_DEFINIDA: ["Sin tarifa para el vehículo", "critico"],
};

const ACCIONES = {
  ACTIVAR_USUARIO: "Activó una cuenta",
  DESACTIVAR_USUARIO: "Desactivó una cuenta",
  AJUSTAR_SALDO: "Ajustó un saldo",
  BLOQUEAR_CREDENCIAL: "Bloqueó una tarjeta",
  DESBLOQUEAR_CREDENCIAL: "Desbloqueó una tarjeta",
  CREAR_GARITA: "Creó una garita",
  MODIFICAR_GARITA: "Modificó una garita",
  CAMBIAR_TARIFA: "Cambió una tarifa",
  ELIMINAR_TARIFA: "Eliminó una tarifa",
  ABRIR_BARRERA: "Abrió la barrera",
  DAR_ADMIN: "Dio rol de administrador",
  QUITAR_ADMIN: "Quitó rol de administrador",
};

const estado = { admin: null, temporizador: null, garitas: null };

/* ---------- Utilidades ---------- */

const formatoPesos = new Intl.NumberFormat("es-CL", { style: "currency", currency: "CLP", maximumFractionDigits: 0 });
const formatoNumero = new Intl.NumberFormat("es-CL");
const pesos = (v) => (v === null || v === undefined ? "—" : (v < 0 ? "−" : "") + formatoPesos.format(Math.abs(v)));
const numero = (v) => formatoNumero.format(v ?? 0);

function pesosCompactos(v) {
  if (Math.abs(v) >= 1e6) return "$" + (v / 1e6).toLocaleString("es-CL", { maximumFractionDigits: 1 }) + "M";
  if (Math.abs(v) >= 1e4) return "$" + (v / 1e3).toLocaleString("es-CL", { maximumFractionDigits: 0 }) + "K";
  return formatoPesos.format(v);
}

/** "2026-11-16" -> "lun 16" (eje del gráfico) */
function diaCorto(iso) {
  const d = new Date(iso + "T00:00:00");
  return d.toLocaleDateString("es-CL", { weekday: "short", day: "numeric" }).replace(",", "");
}

function fecha(texto, conHora = true) {
  if (!texto) return "—";
  const d = new Date(texto.replace(" ", "T"));
  if (Number.isNaN(d.getTime())) return texto;
  const opciones = conHora
    ? { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }
    : { weekday: "short", day: "2-digit", month: "short" };
  return d.toLocaleString("es-CL", opciones);
}

/** Crea un elemento. attrs: clases, atributos y eventos (onClick...). Los hijos de texto van como textContent. */
function el(tag, attrs = {}, ...hijos) {
  const nodo = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue;
    if (k === "class") nodo.className = v;
    else if (k.startsWith("on")) nodo.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === "text") nodo.textContent = v;
    else if (v === true) nodo.setAttribute(k, "");
    else nodo.setAttribute(k, v);
  }
  for (const h of hijos.flat()) {
    if (h === null || h === undefined || h === false) continue;
    nodo.append(h instanceof Node ? h : document.createTextNode(String(h)));
  }
  return nodo;
}

const svg = (tag, attrs = {}) => {
  const nodo = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [k, v] of Object.entries(attrs)) nodo.setAttribute(k, v);
  return nodo;
};

function insigniaEstado(codigo) {
  const [texto, tipo] = ESTADOS[codigo] || [codigo, "neutro"];
  return el("span", { class: `estado estado-${tipo}`, title: codigo }, texto);
}

function insigniaActivo(activo, si = "Activa", no = "Desactivada") {
  return el("span", { class: `estado estado-${activo ? "bien" : "critico"}` }, activo ? si : no);
}

function avisar(mensaje, tipo = "ok") {
  const aviso = el("div", { class: `aviso aviso-${tipo}` }, mensaje);
  $("avisos").append(aviso);
  setTimeout(() => aviso.remove(), tipo === "error" ? 7000 : 4000);
}

function tabla(columnas, filas, alClic) {
  const thead = el("thead", {}, el("tr", {}, columnas.map((c) => el("th", { class: c.num ? "num" : null, scope: "col" }, c.titulo))));
  const tbody = el("tbody");
  for (const fila of filas) {
    const tr = el("tr", alClic ? { class: "fila-enlace", tabindex: "0" } : {});
    for (const c of columnas) {
      const valor = c.valor(fila);
      tr.append(el("td", { class: [c.num ? "num" : "", c.clase || ""].join(" ").trim() || null }, valor));
    }
    if (alClic) {
      tr.addEventListener("click", () => alClic(fila));
      tr.addEventListener("keydown", (e) => { if (e.key === "Enter") alClic(fila); });
    }
    tbody.append(tr);
  }
  return el("div", { class: "tabla-contenedor" }, el("table", {}, thead, tbody));
}

function paginacion(total, limite, offset, irA) {
  const desde = total === 0 ? 0 : offset + 1;
  const hasta = Math.min(offset + limite, total);
  return el("div", { class: "paginacion" },
    el("span", {}, `${numero(desde)}–${numero(hasta)} de ${numero(total)}`),
    el("div", { class: "fila-botones" },
      el("button", { class: "boton boton-secundario boton-chico", type: "button", disabled: offset === 0, onClick: () => irA(Math.max(0, offset - limite)) }, "Anterior"),
      el("button", { class: "boton boton-secundario boton-chico", type: "button", disabled: hasta >= total, onClick: () => irA(offset + limite) }, "Siguiente"),
    ),
  );
}

function encabezado(titulo, descripcion, ...extra) {
  return el("div", { class: "encabezado" },
    el("div", {}, el("h2", {}, titulo), descripcion ? el("p", {}, descripcion) : null),
    ...extra);
}

/* ---------- API ---------- */

class ErrorApi extends Error {
  constructor(mensaje, codigo) { super(mensaje); this.codigo = codigo; }
}

async function api(ruta, { metodo = "GET", cuerpo, sinSesion = false } = {}) {
  const cabeceras = { Accept: "application/json" };
  const token = sessionStorage.getItem(CLAVE_TOKEN);
  if (token && !sinSesion) cabeceras.Authorization = `Bearer ${token}`;
  if (cuerpo !== undefined) cabeceras["Content-Type"] = "application/json";

  let respuesta;
  try {
    respuesta = await fetch(ruta, { method: metodo, headers: cabeceras, body: cuerpo !== undefined ? JSON.stringify(cuerpo) : undefined });
  } catch {
    throw new ErrorApi("No se pudo conectar con el servidor.", 0);
  }
  let datos = null;
  try { datos = await respuesta.json(); } catch { /* respuesta sin JSON */ }

  if (!respuesta.ok) {
    let mensaje = `Error ${respuesta.status}`;
    if (datos && typeof datos.detail === "string") mensaje = datos.detail;
    else if (datos && Array.isArray(datos.detail)) mensaje = "Revisa los datos ingresados.";
    else if (respuesta.status === 429) mensaje = "Demasiados intentos. Espera un minuto.";
    if (!sinSesion && (respuesta.status === 401 || respuesta.status === 403)) {
      cerrarSesion(respuesta.status === 401 ? "Tu sesión expiró. Vuelve a iniciar sesión." : mensaje);
    }
    throw new ErrorApi(mensaje, respuesta.status);
  }
  return datos;
}

/* ---------- Sesión ---------- */

function mostrarLogin(mensaje) {
  detenerRefresco();
  $("pantalla-app").hidden = true;
  $("pantalla-login").hidden = false;
  const error = $("login-error");
  error.hidden = !mensaje;
  error.textContent = mensaje || "";
  $("login-correo").focus();
}

function cerrarSesion(mensaje) {
  $("tooltip").hidden = true;
  sessionStorage.removeItem(CLAVE_TOKEN);
  estado.admin = null;
  estado.garitas = null;
  mostrarLogin(mensaje);
}

function mostrarApp() {
  $("pantalla-login").hidden = true;
  $("pantalla-app").hidden = false;
  $("admin-nombre").textContent = `${estado.admin.nombre} ${estado.admin.apellido}`;
  $("admin-correo").textContent = estado.admin.correo;
  if (!location.hash || location.hash === "#") location.hash = "#/resumen";
  else enrutar();
}

async function iniciarSesion(evento) {
  evento.preventDefault();
  const correo = $("login-correo").value.trim();
  const password = $("login-password").value;
  const error = $("login-error");
  if (!correo || !password) {
    error.textContent = "Ingresa tu correo y contraseña.";
    error.hidden = false;
    return;
  }
  const boton = $("login-boton");
  boton.disabled = true;
  boton.textContent = "Ingresando…";
  try {
    const r = await api("/login", { metodo: "POST", cuerpo: { correo, password }, sinSesion: true });
    if (r.usuario.rol !== "ADMIN") {
      throw new ErrorApi("Esta cuenta no tiene permisos de administrador.", 403);
    }
    sessionStorage.setItem(CLAVE_TOKEN, r.access_token);
    estado.admin = await api("/admin/sesion");
    $("login-password").value = "";
    error.hidden = true;
    mostrarApp();
  } catch (e) {
    sessionStorage.removeItem(CLAVE_TOKEN);
    error.textContent = e.codigo === 401 ? "Correo o contraseña incorrectos." : e.message;
    error.hidden = false;
  } finally {
    boton.disabled = false;
    boton.textContent = "Iniciar sesión";
  }
}

/* ---------- Diálogo de confirmación (los navegadores embebidos bloquean confirm()) ---------- */

function dialogo({ titulo, cuerpo = [], aceptar = "Aceptar", peligro = false, accion }) {
  const d = $("dialogo");
  $("dialogo-titulo").textContent = titulo;
  $("dialogo-cuerpo").replaceChildren(...cuerpo);
  const error = $("dialogo-error");
  error.hidden = true;
  const botonAceptar = $("dialogo-aceptar");
  botonAceptar.textContent = aceptar;
  botonAceptar.className = `boton ${peligro ? "boton-peligro" : "boton-primario"}`;
  botonAceptar.disabled = false;

  $("dialogo-form").onsubmit = async (e) => {
    e.preventDefault();
    botonAceptar.disabled = true;
    try {
      await accion();
      d.close();
    } catch (err) {
      error.textContent = err.message;
      error.hidden = false;
      botonAceptar.disabled = false;
    }
  };
  $("dialogo-cancelar").onclick = () => d.close();
  d.showModal();
  const primerCampo = d.querySelector("#dialogo-cuerpo input, #dialogo-cuerpo textarea, #dialogo-cuerpo select");
  (primerCampo || $("dialogo-cancelar")).focus();
}

/* ---------- Enrutador ---------- */

const VISTAS = {
  resumen: vistaResumen,
  transacciones: vistaTransacciones,
  usuarios: vistaUsuarios,
  garitas: vistaGaritas,
  barrera: vistaBarrera,
  auditoria: vistaAuditoria,
};

function detenerRefresco() {
  if (estado.temporizador) clearInterval(estado.temporizador);
  estado.temporizador = null;
}

async function enrutar() {
  if (!estado.admin) return;
  detenerRefresco();
  $("tooltip").hidden = true;   // si se navega con el mouse sobre el gráfico, el tooltip no debe quedar flotando
  const partes = location.hash.replace(/^#\/?/, "").split("/");
  const seccion = VISTAS[partes[0]] ? partes[0] : "resumen";
  for (const a of document.querySelectorAll("#menu a")) {
    if (a.dataset.seccion === seccion) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  }
  const vista = $("vista");
  vista.replaceChildren(el("p", { class: "cargando" }, "Cargando…"));
  try {
    if (seccion === "usuarios" && partes[1]) await vistaUsuario(vista, Number(partes[1]));
    else await VISTAS[seccion](vista);
  } catch (e) {
    if (e.codigo === 401 || e.codigo === 403) return;
    vista.replaceChildren(el("p", { class: "alerta alerta-error" }, e.message));
  }
}

/* ---------- Gráfico de columnas (recaudación por día) ---------- */

function escalaLimpia(maximo) {
  if (maximo <= 0) return [0, 1];
  const paso0 = maximo / 4;
  const magnitud = 10 ** Math.floor(Math.log10(paso0));
  const paso = [1, 2, 2.5, 5, 10].map((m) => m * magnitud).find((p) => p >= paso0);
  return [0, paso, Math.ceil(maximo / paso) * paso];
}

function graficoColumnas(datos, { etiqueta, valor, formato, formatoEje, descripcion }) {
  const contenedor = el("div", { class: "grafico" });
  const dibujar = () => {
    const ancho = Math.max(280, contenedor.clientWidth || 600);
    const alto = 220;
    const margen = { arriba: 22, derecha: 8, abajo: 26, izquierda: 52 };
    const internoAncho = ancho - margen.izquierda - margen.derecha;
    const internoAlto = alto - margen.arriba - margen.abajo;
    const valores = datos.map(valor);
    const [, paso, tope] = escalaLimpia(Math.max(...valores));
    const y = (v) => margen.arriba + internoAlto - (v / tope) * internoAlto;
    const banda = internoAncho / datos.length;
    const anchoColumna = Math.min(24, banda * 0.6);

    const raiz = svg("svg", { viewBox: `0 0 ${ancho} ${alto}`, role: "img", "aria-label": descripcion });
    for (let v = 0; v <= tope + 1e-9; v += paso) {
      const yy = y(v);
      raiz.append(svg("line", { class: v === 0 ? "linea-base" : "linea-grilla", x1: margen.izquierda, x2: ancho - margen.derecha, y1: yy, y2: yy }));
      const t = svg("text", { class: "eje", x: margen.izquierda - 8, y: yy + 4, "text-anchor": "end" });
      t.textContent = formatoEje(v);
      raiz.append(t);
    }
    const iMax = valores.indexOf(Math.max(...valores));
    datos.forEach((d, i) => {
      const v = valor(d);
      const cx = margen.izquierda + banda * i + banda / 2;
      const x = cx - anchoColumna / 2;
      const yTope = y(v);
      const h = margen.arriba + internoAlto - yTope;
      // Zona de interacción más grande que la columna (toda la banda)
      const zona = svg("rect", { class: "columna-zona", x: margen.izquierda + banda * i, y: margen.arriba, width: banda, height: internoAlto, tabindex: "0", "aria-label": `${etiqueta(d)}: ${formato(v)}` });
      raiz.append(zona);
      if (h > 0.5) {
        const r = Math.min(4, h, anchoColumna / 2);
        const base = margen.arriba + internoAlto;
        // Punta redondeada de 4px, base recta sobre el eje
        raiz.append(svg("path", {
          class: "columna",
          d: `M${x},${base} V${yTope + r} Q${x},${yTope} ${x + r},${yTope} H${x + anchoColumna - r} Q${x + anchoColumna},${yTope} ${x + anchoColumna},${yTope + r} V${base} Z`,
        }));
      }
      // Etiquetas directas sólo en el máximo y en el último día (hoy)
      if ((i === iMax || i === datos.length - 1) && v > 0) {
        const t = svg("text", { class: "valor", x: cx, y: yTope - 6, "text-anchor": "middle" });
        t.textContent = formatoEje(v);
        raiz.append(t);
      }
      const eje = svg("text", { class: "eje", x: cx, y: alto - 8, "text-anchor": "middle" });
      eje.textContent = i === datos.length - 1 ? "Hoy" : etiqueta(d, true);
      raiz.append(eje);

      const mostrar = () => {
        const caja = zona.getBoundingClientRect();
        const tip = $("tooltip");
        tip.replaceChildren(el("strong", {}, etiqueta(d)), el("span", {}, formato(v)), el("br"), el("span", {}, `${numero(d.pasos)} pasos`));
        tip.hidden = false;
        const ancho = tip.offsetWidth;
        tip.style.left = `${Math.min(window.innerWidth - ancho - 8, Math.max(8, caja.left + caja.width / 2 - ancho / 2))}px`;
        tip.style.top = `${Math.max(8, caja.top + (yTope / alto) * caja.height - tip.offsetHeight - 10)}px`;
      };
      const ocultar = () => { $("tooltip").hidden = true; };
      zona.addEventListener("mouseenter", mostrar);
      zona.addEventListener("focus", mostrar);
      zona.addEventListener("mouseleave", ocultar);
      zona.addEventListener("blur", ocultar);
    });
    contenedor.replaceChildren(raiz);
  };
  new ResizeObserver(() => dibujar()).observe(contenedor);
  requestAnimationFrame(dibujar);
  return contenedor;
}

/* ---------- Vista: Resumen ---------- */

async function vistaResumen(vista) {
  const pintar = async () => {
    const r = await api("/admin/resumen");
    const p = r.pasos_hoy;
    const indicadores = el("div", { class: "indicadores" },
      indicador("Recaudado hoy", pesos(p.recaudado), `${numero(p.aprobados)} pasos aprobados`),
      indicador("Pasos hoy", numero(p.pasos), `${numero(p.rechazados)} rechazados`),
      indicador("Recargas hoy", pesos(r.recargas_hoy.monto), `${numero(r.recargas_hoy.cantidad)} recargas`),
      indicador("Usuarios activos", numero(r.usuarios.activos), `de ${numero(r.usuarios.total)} registrados`),
      indicador("Saldo en cuentas", pesos(r.usuarios.saldo_total), "suma de todos los usuarios"),
    );

    const serie = r.ultimos_7_dias;
    const grafico = el("section", { class: "tarjeta" },
      el("h3", {}, "Recaudación de los últimos 7 días"),
      el("span", { class: "sub" }, "Cobros aprobados por día"),
      serie.some((d) => d.recaudado > 0)
        ? graficoColumnas(serie, {
            etiqueta: (d, corto) => (corto ? diaCorto(d.dia) : fecha(d.dia + " 00:00:00", false)),
            valor: (d) => d.recaudado,
            formato: pesos,
            formatoEje: pesosCompactos,
            descripcion: "Gráfico de columnas: recaudación diaria de los últimos 7 días",
          })
        : el("p", { class: "vacio" }, "Sin cobros en los últimos 7 días."),
      el("details", { class: "tabla-vista" },
        el("summary", {}, "Ver como tabla"),
        tabla([
          { titulo: "Día", valor: (d) => fecha(d.dia + " 00:00:00", false) },
          { titulo: "Pasos", num: true, valor: (d) => numero(d.pasos) },
          { titulo: "Recaudado", num: true, valor: (d) => pesos(d.recaudado) },
        ], serie)),
    );

    const maxRechazo = Math.max(1, ...r.rechazos_7_dias.map((x) => x.cantidad));
    const rechazos = el("section", { class: "tarjeta" },
      el("h3", {}, "Rechazos de los últimos 7 días"),
      el("span", { class: "sub" }, "Por motivo"),
      r.rechazos_7_dias.length
        ? el("div", { class: "barras" }, r.rechazos_7_dias.map((x) => {
            const barra = el("span", { class: "barra" });
            barra.style.width = `${(x.cantidad / maxRechazo) * 100}%`;
            return el("div", { class: "barra-fila" },
              el("span", { class: "barra-nombre" }, (ESTADOS[x.estado] || [x.estado])[0]),
              el("span", { class: "barra-pista" }, barra, el("span", { class: "barra-valor" }, numero(x.cantidad))));
          }))
        : el("p", { class: "vacio" }, "Sin rechazos en los últimos 7 días."),
    );

    const garitas = el("section", { class: "tarjeta" },
      el("h3", {}, "Garitas hoy"),
      el("span", { class: "sub" }, "Actividad de cada garita desde las 00:00"),
      tabla([
        { titulo: "Garita", valor: (g) => el("span", {}, g.nombre, " ", el("span", { class: "texto-3 mono" }, g.garita)) },
        { titulo: "Estado", valor: (g) => insigniaActivo(g.activa) },
        { titulo: "Pasos", num: true, valor: (g) => numero(g.pasos) },
        { titulo: "Recaudado", num: true, valor: (g) => pesos(g.recaudado) },
        { titulo: "Último paso", valor: (g) => fecha(g.ultimo_paso) },
      ], r.garitas),
    );

    const ultimas = el("section", { class: "tarjeta" },
      el("h3", {}, "Últimos pasos"),
      el("span", { class: "sub" }, "Se actualiza cada 20 segundos · ", el("a", { href: "#/transacciones" }, "Ver todos")),
      r.ultimas_transacciones.length
        ? tablaTransacciones(r.ultimas_transacciones, { compacta: true })
        : el("p", { class: "vacio" }, "Todavía no hay pasos registrados."),
    );

    vista.replaceChildren(
      encabezado("Resumen", `Hoy, ${fecha(r.hoy + " 00:00:00", false)}`,
        el("button", { class: "boton boton-secundario boton-chico", type: "button", onClick: () => pintar().catch(() => {}) }, "Actualizar")),
      indicadores,
      el("div", { class: "grilla grilla-2" }, grafico, rechazos),
      el("div", { class: "pila espacio-arriba" }, garitas, ultimas),
    );
  };
  await pintar();
  estado.temporizador = setInterval(() => { if (!document.hidden) pintar().catch(() => {}); }, 20000);
}

function indicador(etiqueta, valor, detalle) {
  return el("div", { class: "indicador" },
    el("div", { class: "indicador-etiqueta" }, etiqueta),
    el("div", { class: "indicador-valor" }, valor),
    detalle ? el("div", { class: "indicador-detalle" }, detalle) : null);
}

function tablaTransacciones(filas, { compacta = false } = {}) {
  const columnas = [
    { titulo: "Fecha", valor: (t) => fecha(t.fecha_hora) },
    { titulo: "Garita / carril", valor: (t) => `${t.garita} · ${t.carril}` },
    { titulo: "Usuario", valor: (t) => t.usuario_id ? el("a", { href: `#/usuarios/${t.usuario_id}` }, t.usuario) : el("span", { class: "texto-3" }, "—") },
    { titulo: "Patente", valor: (t) => el("span", { class: "mono" }, t.patente || "—") },
    { titulo: "Método", valor: (t) => t.metodo === "TOKEN" ? "Celular (NFC)" : "Tarjeta (UID)" },
    { titulo: "Cobro", num: true, valor: (t) => pesos(t.tarifa_cobrada) },
    { titulo: "Resultado", valor: (t) => insigniaEstado(t.estado) },
  ];
  if (!compacta) columnas.splice(6, 0, { titulo: "Saldo restante", num: true, valor: (t) => pesos(t.saldo_restante) });
  return tabla(columnas, filas);
}

/* ---------- Vista: Transacciones ---------- */

async function vistaTransacciones(vista) {
  const filtros = { garita: "", estado: "", q: "", desde: "", hasta: "", offset: 0 };
  const limite = 50;
  const resultados = el("div");
  if (!estado.garitas) estado.garitas = (await api("/admin/garitas")).garitas;

  const cargar = async () => {
    const params = new URLSearchParams({ limite, offset: filtros.offset });
    for (const k of ["garita", "estado", "q", "desde", "hasta"]) if (filtros[k]) params.set(k, filtros[k]);
    resultados.replaceChildren(el("p", { class: "cargando" }, "Cargando…"));
    try {
      const r = await api(`/admin/transacciones?${params}`);
      resultados.replaceChildren(
        r.transacciones.length ? tablaTransacciones(r.transacciones) : el("p", { class: "vacio" }, "No hay pasos con esos filtros."),
        paginacion(r.total, r.limite, r.offset, (o) => { filtros.offset = o; cargar(); }),
      );
    } catch (e) {
      resultados.replaceChildren(el("p", { class: "alerta alerta-error" }, e.message));
    }
  };

  const selectGarita = el("select", { onChange: (e) => { filtros.garita = e.target.value; filtros.offset = 0; cargar(); } },
    el("option", { value: "" }, "Todas"),
    estado.garitas.map((g) => el("option", { value: g.codigo }, g.nombre)));
  const selectEstado = el("select", { onChange: (e) => { filtros.estado = e.target.value; filtros.offset = 0; cargar(); } },
    el("option", { value: "" }, "Todos"),
    el("option", { value: "APROBADO" }, "Aprobados"),
    el("option", { value: "RECHAZADO" }, "Todos los rechazos"),
    Object.entries(ESTADOS).filter(([k]) => k !== "APROBADO").map(([k, [t]]) => el("option", { value: k }, t)));
  let espera;
  const buscar = el("input", { type: "search", placeholder: "Patente, correo o nombre", onInput: (e) => {
    clearTimeout(espera);
    espera = setTimeout(() => { filtros.q = e.target.value.trim(); filtros.offset = 0; cargar(); }, 350);
  } });
  const fechaCampo = (clave) => el("input", { type: "date", onChange: (e) => { filtros[clave] = e.target.value; filtros.offset = 0; cargar(); } });

  vista.replaceChildren(
    encabezado("Pasos por la barrera", "Cada lectura de los lectores RFID/NFC, aprobada o rechazada."),
    el("section", { class: "tarjeta" },
      el("div", { class: "filtros" },
        el("label", { class: "campo campo-ancho" }, el("span", {}, "Buscar"), buscar),
        el("label", { class: "campo" }, el("span", {}, "Garita"), selectGarita),
        el("label", { class: "campo" }, el("span", {}, "Resultado"), selectEstado),
        el("label", { class: "campo" }, el("span", {}, "Desde"), fechaCampo("desde")),
        el("label", { class: "campo" }, el("span", {}, "Hasta"), fechaCampo("hasta")),
      ),
      resultados),
  );
  await cargar();
}

/* ---------- Vista: Usuarios ---------- */

async function vistaUsuarios(vista) {
  const filtros = { q: "", offset: 0 };
  const resultados = el("div");
  const cargar = async () => {
    const params = new URLSearchParams({ limite: 50, offset: filtros.offset });
    if (filtros.q) params.set("q", filtros.q);
    try {
      const r = await api(`/admin/usuarios?${params}`);
      resultados.replaceChildren(
        r.usuarios.length
          ? tabla([
              { titulo: "Nombre", valor: (u) => el("span", {}, `${u.nombre} ${u.apellido} `, u.rol === "ADMIN" ? el("span", { class: "etiqueta etiqueta-acento" }, "Admin") : null) },
              { titulo: "Correo", valor: (u) => u.correo },
              { titulo: "Patente", valor: (u) => el("span", { class: "mono" }, u.patente) },
              { titulo: "Vehículo", valor: (u) => u.tipo_vehiculo },
              { titulo: "Saldo", num: true, valor: (u) => pesos(u.saldo) },
              { titulo: "Cuenta", valor: (u) => insigniaActivo(u.activo) },
            ], r.usuarios, (u) => { location.hash = `#/usuarios/${u.id}`; })
          : el("p", { class: "vacio" }, "No se encontraron usuarios."),
        paginacion(r.total, r.limite, r.offset, (o) => { filtros.offset = o; cargar(); }),
      );
    } catch (e) {
      resultados.replaceChildren(el("p", { class: "alerta alerta-error" }, e.message));
    }
  };
  let espera;
  vista.replaceChildren(
    encabezado("Usuarios", "Haz clic en un usuario para ver su detalle, ajustar su saldo o bloquear sus tarjetas."),
    el("section", { class: "tarjeta" },
      el("div", { class: "filtros" },
        el("label", { class: "campo campo-ancho" }, el("span", {}, "Buscar"),
          el("input", { type: "search", placeholder: "Nombre, correo o patente", onInput: (e) => {
            clearTimeout(espera);
            espera = setTimeout(() => { filtros.q = e.target.value.trim(); filtros.offset = 0; cargar(); }, 350);
          } }))),
      resultados),
  );
  await cargar();
}

async function vistaUsuario(vista, id) {
  const r = await api(`/admin/usuarios/${id}`);
  const u = r.usuario;
  const esYo = u.id === estado.admin.id;

  const cambiarEstado = () => dialogo({
    titulo: u.activo ? "¿Desactivar esta cuenta?" : "¿Activar esta cuenta?",
    cuerpo: [el("p", {}, u.activo
      ? `${u.nombre} ${u.apellido} no podrá iniciar sesión ni pagar en la barrera hasta que la actives de nuevo.`
      : `${u.nombre} ${u.apellido} podrá volver a iniciar sesión y pagar en la barrera.`)],
    aceptar: u.activo ? "Desactivar" : "Activar",
    peligro: u.activo,
    accion: async () => {
      const res = await api(`/admin/usuarios/${id}/estado`, { metodo: "PUT", cuerpo: { activo: !u.activo } });
      avisar(res.mensaje);
      enrutar();
    },
  });

  const ajustarSaldo = () => {
    const tipo = el("select", {}, el("option", { value: "1" }, "Abonar (sumar)"), el("option", { value: "-1" }, "Descontar (restar)"));
    const monto = el("input", { type: "number", min: "1", step: "1", inputmode: "numeric", required: true, placeholder: "Ej. 5000" });
    const motivo = el("textarea", { required: true, minlength: "5", maxlength: "200", placeholder: "Ej. Devolución por cobro duplicado del 12/11" });
    dialogo({
      titulo: "Ajustar saldo",
      cuerpo: [
        el("p", {}, `Saldo actual de ${u.nombre}: `, el("strong", {}, pesos(u.saldo))),
        el("label", { class: "campo" }, el("span", {}, "Tipo de ajuste"), tipo),
        el("label", { class: "campo" }, el("span", {}, "Monto"), monto),
        el("label", { class: "campo" }, el("span", {}, "Motivo (queda en la auditoría)"), motivo),
      ],
      aceptar: "Guardar ajuste",
      accion: async () => {
        const valor = Number(monto.value);
        if (!Number.isFinite(valor) || valor <= 0) throw new Error("Ingresa un monto mayor a cero.");
        if (motivo.value.trim().length < 5) throw new Error("Escribe un motivo de al menos 5 caracteres.");
        const res = await api(`/admin/usuarios/${id}/ajuste-saldo`, {
          metodo: "POST", cuerpo: { monto: valor * Number(tipo.value), motivo: motivo.value.trim() },
        });
        avisar(`Saldo ajustado: ${pesos(res.saldo_anterior)} → ${pesos(res.saldo_nuevo)}`);
        enrutar();
      },
    });
  };

  const cambiarCredencial = (c) => dialogo({
    titulo: c.activa ? "¿Bloquear esta tarjeta?" : "¿Desbloquear esta tarjeta?",
    cuerpo: [el("p", {}, c.activa
      ? `La barrera rechazará la credencial ${c.uid} (estado "Tarjeta bloqueada"). Úsalo si la tarjeta se perdió o fue robada.`
      : `La credencial ${c.uid} volverá a funcionar en la barrera.`)],
    aceptar: c.activa ? "Bloquear" : "Desbloquear",
    peligro: c.activa,
    accion: async () => {
      const res = await api(`/admin/credenciales/${c.id}/estado`, { metodo: "PUT", cuerpo: { activa: !c.activa } });
      avisar(res.mensaje);
      enrutar();
    },
  });

  vista.replaceChildren(
    el("a", { class: "volver", href: "#/usuarios" }, "← Usuarios"),
    encabezado(`${u.nombre} ${u.apellido}`, u.correo,
      el("div", { class: "fila-botones" },
        el("button", { class: "boton boton-primario", type: "button", onClick: ajustarSaldo }, "Ajustar saldo"),
        esYo ? null : el("button", { class: "boton boton-secundario", type: "button", onClick: cambiarEstado }, u.activo ? "Desactivar cuenta" : "Activar cuenta"))),
    el("div", { class: "grilla grilla-2" },
      el("section", { class: "tarjeta" },
        el("h3", {}, "Saldo"),
        el("div", { class: "saldo-grande" }, pesos(u.saldo)),
        el("dl", { class: "datos" },
          el("dt", {}, "Cuenta"), el("dd", {}, insigniaActivo(u.activo)),
          el("dt", {}, "Rol"), el("dd", {}, u.rol === "ADMIN" ? "Administrador" : "Usuario"),
          el("dt", {}, "Patente"), el("dd", { class: "mono" }, u.patente),
          el("dt", {}, "Vehículo"), el("dd", {}, u.tipo_vehiculo_nombre),
          el("dt", {}, "Registrado"), el("dd", {}, fecha(u.creado_en)))),
      el("section", { class: "tarjeta" },
        el("h3", {}, "Tarjetas y credenciales"),
        el("span", { class: "sub" }, "El UID virtual es el del celular; las tarjetas son RFID físicas."),
        r.credenciales.length
          ? tabla([
              { titulo: "Tipo", valor: (c) => c.tipo === "VIRTUAL" ? "UID virtual" : (c.alias || "Tarjeta") },
              { titulo: "UID", valor: (c) => el("span", { class: "mono" }, c.uid) },
              { titulo: "Estado", valor: (c) => insigniaActivo(c.activa, "Activa", "Bloqueada") },
              { titulo: "", valor: (c) => el("button", { class: "boton boton-secundario boton-chico", type: "button", onClick: () => cambiarCredencial(c) }, c.activa ? "Bloquear" : "Desbloquear") },
            ], r.credenciales)
          : el("p", { class: "vacio" }, "Sin credenciales."))),
    el("div", { class: "pila espacio-arriba" },
      el("section", { class: "tarjeta" },
        el("h3", {}, "Últimos pasos"),
        el("span", { class: "sub" }, el("a", { href: `#/transacciones` }, "Ver todos los pasos")),
        r.pasos.length
          ? tabla([
              { titulo: "Fecha", valor: (t) => fecha(t.fecha_hora) },
              { titulo: "Garita / carril", valor: (t) => `${t.garita} · ${t.carril}` },
              { titulo: "Método", valor: (t) => t.metodo === "TOKEN" ? "Celular (NFC)" : "Tarjeta (UID)" },
              { titulo: "Cobro", num: true, valor: (t) => pesos(t.tarifa_cobrada) },
              { titulo: "Saldo restante", num: true, valor: (t) => pesos(t.saldo_restante) },
              { titulo: "Resultado", valor: (t) => insigniaEstado(t.estado) },
            ], r.pasos)
          : el("p", { class: "vacio" }, "Todavía no pasa por ninguna garita.")),
      el("section", { class: "tarjeta" },
        el("h3", {}, "Recargas y ajustes"),
        r.movimientos.length
          ? tabla([
              { titulo: "Fecha", valor: (m) => fecha(m.fecha_hora) },
              { titulo: "Tipo", valor: (m) => m.tipo === "RECARGA" ? "Recarga (app)" : `Ajuste (${m.admin || "admin"})` },
              { titulo: "Monto", num: true, valor: (m) => (m.monto > 0 ? "+" : "") + pesos(m.monto) },  // pesos() ya antepone "−"
              { titulo: "Saldo", num: true, valor: (m) => `${pesos(m.saldo_anterior)} → ${pesos(m.saldo_nuevo)}` },
              { titulo: "Motivo", clase: "ajustar", valor: (m) => m.motivo || el("span", { class: "texto-3" }, "—") },
            ], r.movimientos)
          : el("p", { class: "vacio" }, "Sin recargas ni ajustes."))),
  );
}

/* ---------- Vista: Garitas y tarifas ---------- */

async function vistaGaritas(vista) {
  const r = await api("/admin/garitas");
  estado.garitas = r.garitas;

  const nuevaGarita = () => {
    const codigo = el("input", { required: true, pattern: "[a-z0-9_]{3,50}", placeholder: "garita_sur", autocomplete: "off" });
    const nombre = el("input", { required: true, maxlength: "100", placeholder: "Garita Sur" });
    dialogo({
      titulo: "Nueva garita",
      cuerpo: [
        el("label", { class: "campo" }, el("span", {}, "Código"), codigo,
          el("p", { class: "ayuda" }, "Minúsculas, números y _. Debe ser igual al GARITA_ID del ESP32 y a su usuario en MQTT_DISPOSITIVOS del .env.")),
        el("label", { class: "campo" }, el("span", {}, "Nombre"), nombre),
      ],
      aceptar: "Crear garita",
      accion: async () => {
        if (!/^[a-z0-9_]{3,50}$/.test(codigo.value.trim())) throw new Error("Código: 3 a 50 letras minúsculas, números o _.");
        if (!nombre.value.trim()) throw new Error("Escribe el nombre de la garita.");
        await api("/admin/garitas", { metodo: "POST", cuerpo: { codigo: codigo.value.trim(), nombre: nombre.value.trim() } });
        avisar("Garita creada. Define sus tarifas para que pueda cobrar.");
        enrutar();
      },
    });
  };

  const tarjetas = r.garitas.map((g) => {
    const filas = g.tarifas.map((t) => {
      const entrada = el("input", { class: "tarifa-input", type: "number", min: "0", step: "1", inputmode: "numeric", value: t.monto ?? "", placeholder: "Sin tarifa", "aria-label": `Tarifa ${t.tipo_vehiculo_nombre}` });
      const guardar = el("button", { class: "boton boton-secundario boton-chico", type: "button", onClick: async () => {
        if (entrada.value === "") return avisar("Ingresa un monto o usa Quitar.", "error");
        guardar.disabled = true;
        try {
          const res = await api("/admin/tarifas", { metodo: "PUT", cuerpo: { garita_id: g.id, tipo_vehiculo: t.tipo_vehiculo, monto: Number(entrada.value) } });
          avisar(`${g.nombre} · ${t.tipo_vehiculo_nombre}: ${pesos(res.monto)}`);
          enrutar();
        } catch (e) { avisar(e.message, "error"); guardar.disabled = false; }
      } }, "Guardar");
      const quitar = t.monto === null ? null : el("button", { class: "boton boton-secundario boton-chico", type: "button", onClick: () => dialogo({
        titulo: "¿Quitar esta tarifa?",
        cuerpo: [el("p", {}, `Los vehículos tipo ${t.tipo_vehiculo_nombre} serán rechazados en ${g.nombre} (Sin tarifa para el vehículo) hasta que definas un monto.`)],
        aceptar: "Quitar tarifa", peligro: true,
        accion: async () => {
          await api(`/admin/tarifas/${g.id}/${t.tipo_vehiculo}`, { metodo: "DELETE" });
          avisar("Tarifa eliminada");
          enrutar();
        },
      }) }, "Quitar");
      return { t, entrada, acciones: el("div", { class: "fila-botones" }, guardar, quitar) };
    });

    const cambiarEstado = () => dialogo({
      titulo: g.activa ? "¿Desactivar la garita?" : "¿Activar la garita?",
      cuerpo: [el("p", {}, g.activa
        ? `Todas las lecturas de ${g.nombre} serán rechazadas (Lectura inválida) y la barrera no abrirá.`
        : `${g.nombre} volverá a cobrar y abrir la barrera.`)],
      aceptar: g.activa ? "Desactivar" : "Activar", peligro: g.activa,
      accion: async () => {
        await api(`/admin/garitas/${g.id}`, { metodo: "PUT", cuerpo: { activa: !g.activa } });
        avisar(g.activa ? "Garita desactivada" : "Garita activada");
        enrutar();
      },
    });

    const renombrar = () => {
      const nombre = el("input", { required: true, maxlength: "100", value: g.nombre });
      dialogo({
        titulo: "Cambiar nombre",
        cuerpo: [el("label", { class: "campo" }, el("span", {}, "Nombre"), nombre)],
        aceptar: "Guardar",
        accion: async () => {
          if (!nombre.value.trim()) throw new Error("El nombre no puede quedar vacío.");
          await api(`/admin/garitas/${g.id}`, { metodo: "PUT", cuerpo: { nombre: nombre.value.trim() } });
          avisar("Nombre actualizado");
          enrutar();
        },
      });
    };

    return el("section", { class: "tarjeta" },
      el("div", { class: "garita-cabecera" },
        el("div", {},
          el("h3", {}, g.nombre),
          el("span", { class: "sub mono" }, `${g.codigo} · carriles vistos: ${g.carriles.length ? g.carriles.join(", ") : "ninguno"}`)),
        el("div", { class: "fila-botones" },
          insigniaActivo(g.activa),
          el("button", { class: "boton boton-secundario boton-chico", type: "button", onClick: renombrar }, "Renombrar"),
          el("button", { class: "boton boton-secundario boton-chico", type: "button", onClick: cambiarEstado }, g.activa ? "Desactivar" : "Activar"))),
      tabla([
        { titulo: "Tipo de vehículo", valor: (f) => f.t.tipo_vehiculo_nombre },
        { titulo: "Tarifa actual", num: true, valor: (f) => f.t.monto === null ? el("span", { class: "estado estado-critico" }, "Sin tarifa") : pesos(f.t.monto) },
        { titulo: "Nuevo monto", valor: (f) => f.entrada },
        { titulo: "", valor: (f) => f.acciones },
      ], filas));
  });

  vista.replaceChildren(
    encabezado("Garitas y tarifas", "La tarifa se cobra según la garita y el tipo de vehículo del usuario. Sin tarifa, el paso se rechaza.",
      el("button", { class: "boton boton-primario", type: "button", onClick: nuevaGarita }, "Nueva garita")),
    el("div", { class: "pila" }, tarjetas.length ? tarjetas : el("p", { class: "vacio" }, "No hay garitas.")),
  );
}

/* ---------- Vista: Abrir barrera ---------- */

async function vistaBarrera(vista) {
  const r = await api("/admin/garitas");
  estado.garitas = r.garitas;
  const activas = r.garitas.filter((g) => g.activa);
  const resultado = el("div");

  const selectGarita = el("select", { required: true });
  activas.forEach((g) => selectGarita.append(el("option", { value: g.id }, g.nombre)));
  const carril = el("input", { required: true, list: "carriles", placeholder: "carril1", autocomplete: "off" });
  const listaCarriles = el("datalist", { id: "carriles" });
  const actualizarCarriles = () => {
    const g = activas.find((x) => String(x.id) === selectGarita.value);
    listaCarriles.replaceChildren(...(g ? g.carriles : []).map((c) => el("option", { value: c })));
    if (g && g.carriles.length && !carril.value) carril.value = g.carriles[0];
  };
  selectGarita.addEventListener("change", () => { carril.value = ""; actualizarCarriles(); });
  actualizarCarriles();
  const motivo = el("textarea", { required: true, minlength: "5", maxlength: "200", placeholder: "Ej. Lector sin conexión, ambulancia, prueba de funcionamiento" });

  const enviar = (e) => {
    e.preventDefault();
    if (!selectGarita.value) return;
    if (!carril.value.trim()) return avisar("Indica el carril.", "error");
    if (motivo.value.trim().length < 5) return avisar("Escribe el motivo (mínimo 5 caracteres).", "error");
    const g = activas.find((x) => String(x.id) === selectGarita.value);
    dialogo({
      titulo: "¿Abrir la barrera ahora?",
      cuerpo: [el("p", {}, `Se enviará la orden ABRIR a ${g.nombre}, carril ${carril.value.trim()}. El paso no se cobra y queda registrado en la auditoría.`)],
      aceptar: "Abrir barrera", peligro: true,
      accion: async () => {
        const res = await api("/admin/barrera/abrir", { metodo: "POST", cuerpo: { garita_id: g.id, carril: carril.value.trim(), motivo: motivo.value.trim() } });
        resultado.replaceChildren(el("p", { class: "alerta alerta-ok" }, `${res.mensaje}. La barrera se cerrará sola cuando pase el vehículo.`));
        motivo.value = "";
      },
    });
  };

  vista.replaceChildren(
    encabezado("Abrir barrera", "Apertura manual desde el panel, por ejemplo si el lector falla o pasa un vehículo de emergencia."),
    el("section", { class: "tarjeta" },
      activas.length
        ? el("form", { class: "formulario", novalidate: true, onSubmit: enviar },
            el("label", { class: "campo" }, el("span", {}, "Garita"), selectGarita),
            el("label", { class: "campo" }, el("span", {}, "Carril"), carril, listaCarriles,
              el("p", { class: "ayuda" }, "Debe coincidir con el CARRIL_ID del ESP32. Se sugieren los carriles que ya registraron pasos.")),
            el("label", { class: "campo" }, el("span", {}, "Motivo"), motivo),
            el("div", { class: "fila-botones" }, el("button", { class: "boton boton-peligro", type: "submit" }, "Abrir barrera")),
            resultado)
        : el("p", { class: "vacio" }, "No hay garitas activas.")),
  );
}

/* ---------- Vista: Auditoría ---------- */

async function vistaAuditoria(vista) {
  let offset = 0;
  const resultados = el("div");
  const cargar = async () => {
    const r = await api(`/admin/auditoria?limite=50&offset=${offset}`);
    resultados.replaceChildren(
      r.auditoria.length
        ? tabla([
            { titulo: "Fecha", valor: (a) => fecha(a.fecha_hora) },
            { titulo: "Administrador", valor: (a) => a.admin || el("span", { class: "texto-3" }, a.ip === "consola" ? "Consola del servidor" : "—") },
            { titulo: "Acción", valor: (a) => ACCIONES[a.accion] || a.accion },
            { titulo: "Detalle", clase: "ajustar", valor: (a) => a.detalle || "—" },
            { titulo: "IP", valor: (a) => el("span", { class: "mono texto-2" }, a.ip || "—") },
          ], r.auditoria)
        : el("p", { class: "vacio" }, "Todavía no hay acciones registradas."),
      paginacion(r.total, r.limite, r.offset, (o) => { offset = o; cargar(); }),
    );
  };
  vista.replaceChildren(
    encabezado("Auditoría", "Registro de cada cambio hecho desde el panel o la consola: quién, qué y cuándo."),
    el("section", { class: "tarjeta" }, resultados),
  );
  await cargar();
}

/* ---------- Inicio ---------- */

async function iniciar() {
  $("form-login").addEventListener("submit", iniciarSesion);
  $("boton-salir").addEventListener("click", () => cerrarSesion());
  window.addEventListener("hashchange", enrutar);

  if (!sessionStorage.getItem(CLAVE_TOKEN)) return mostrarLogin();
  try {
    estado.admin = await api("/admin/sesion");
    mostrarApp();
  } catch (e) {
    if (e.codigo !== 401 && e.codigo !== 403) cerrarSesion(e.message);
  }
}

iniciar();
