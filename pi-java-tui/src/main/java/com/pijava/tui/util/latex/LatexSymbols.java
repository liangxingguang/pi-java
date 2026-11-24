package com.pijava.tui.util.latex;

import java.util.Map;
import java.util.Set;

/**
 * LaTeX 符号表（L7 W7，R8）：pi {@code tui/src/latex.ts} 的 SYMBOLS /
 * SUPERSCRIPTS / SUBSCRIPTS / ACCENTS 与命令集合的 java 对位子集
 * （窄差 N-W7-4：未收录的长尾符号回落原文）。
 */
public final class LatexSymbols {

    private LatexSymbols() {}

    /** 命令 → Unicode 符号。 */
    public static final Map<String, String> SYMBOLS = Map.ofEntries(
        Map.entry("alpha", "α"), Map.entry("beta", "β"), Map.entry("gamma", "γ"),
        Map.entry("delta", "δ"), Map.entry("epsilon", "ϵ"), Map.entry("varepsilon", "ε"),
        Map.entry("zeta", "ζ"), Map.entry("eta", "η"), Map.entry("theta", "θ"),
        Map.entry("vartheta", "ϑ"), Map.entry("iota", "ι"), Map.entry("kappa", "κ"),
        Map.entry("varkappa", "ϰ"), Map.entry("lambda", "λ"), Map.entry("mu", "μ"),
        Map.entry("nu", "ν"), Map.entry("xi", "ξ"), Map.entry("pi", "π"),
        Map.entry("varpi", "ϖ"), Map.entry("rho", "ρ"), Map.entry("varrho", "ϱ"),
        Map.entry("sigma", "σ"), Map.entry("varsigma", "ς"), Map.entry("tau", "τ"),
        Map.entry("upsilon", "υ"), Map.entry("phi", "ϕ"), Map.entry("varphi", "φ"),
        Map.entry("chi", "χ"), Map.entry("psi", "ψ"), Map.entry("omega", "ω"),
        Map.entry("Gamma", "Γ"), Map.entry("Delta", "Δ"), Map.entry("Theta", "Θ"),
        Map.entry("Lambda", "Λ"), Map.entry("Xi", "Ξ"), Map.entry("Pi", "Π"),
        Map.entry("Sigma", "Σ"), Map.entry("Upsilon", "Υ"), Map.entry("Phi", "Φ"),
        Map.entry("Psi", "Ψ"), Map.entry("Omega", "Ω"),
        Map.entry("pm", "±"), Map.entry("mp", "∓"), Map.entry("times", "×"),
        Map.entry("div", "÷"), Map.entry("cdot", "·"), Map.entry("ast", "∗"),
        Map.entry("star", "⋆"), Map.entry("circ", "∘"), Map.entry("bullet", "•"),
        Map.entry("oplus", "⊕"), Map.entry("ominus", "⊖"), Map.entry("otimes", "⊗"),
        Map.entry("oslash", "⊘"), Map.entry("odot", "⊙"), Map.entry("bigcirc", "○"),
        Map.entry("dagger", "†"), Map.entry("ddagger", "‡"), Map.entry("amalg", "⨿"),
        Map.entry("uplus", "⊎"), Map.entry("sqcap", "⊓"), Map.entry("sqcup", "⊔"),
        Map.entry("bowtie", "⋈"), Map.entry("Join", "⋈"), Map.entry("ltimes", "⋉"),
        Map.entry("rtimes", "⋊"), Map.entry("triangleleft", "◁"),
        Map.entry("triangleright", "▷"), Map.entry("wr", "≀"),
        Map.entry("cap", "∩"), Map.entry("cup", "∪"), Map.entry("bigcap", "⋂"),
        Map.entry("bigcup", "⋃"), Map.entry("bigwedge", "⋀"), Map.entry("bigvee", "⋁"),
        Map.entry("bigsqcup", "⨆"), Map.entry("biguplus", "⨄"), Map.entry("bigoplus", "⨁"),
        Map.entry("bigotimes", "⨂"), Map.entry("bigodot", "⨀"), Map.entry("setminus", "∖"),
        Map.entry("in", "∈"), Map.entry("notin", "∉"), Map.entry("ni", "∋"),
        Map.entry("subset", "⊂"), Map.entry("supset", "⊃"), Map.entry("subseteq", "⊆"),
        Map.entry("supseteq", "⊇"), Map.entry("sqsubset", "⊏"), Map.entry("sqsupset", "⊐"),
        Map.entry("sqsubseteq", "⊑"), Map.entry("sqsupseteq", "⊒"),
        Map.entry("prec", "≺"), Map.entry("preceq", "≼"), Map.entry("succ", "≻"),
        Map.entry("succeq", "≽"), Map.entry("ll", "≪"), Map.entry("gg", "≫"),
        Map.entry("le", "≤"), Map.entry("leq", "≤"), Map.entry("leqslant", "≤"),
        Map.entry("ge", "≥"), Map.entry("geq", "≥"), Map.entry("geqslant", "≥"),
        Map.entry("ne", "≠"), Map.entry("neq", "≠"), Map.entry("equiv", "≡"),
        Map.entry("approx", "≈"), Map.entry("sim", "∼"), Map.entry("simeq", "≃"),
        Map.entry("cong", "≅"), Map.entry("asymp", "≍"), Map.entry("doteq", "≐"),
        Map.entry("propto", "∝"), Map.entry("parallel", "∥"), Map.entry("perp", "⊥"),
        Map.entry("mid", "∣"), Map.entry("vdash", "⊢"), Map.entry("dashv", "⊣"),
        Map.entry("models", "⊨"), Map.entry("Vdash", "⊩"), Map.entry("Vvdash", "⊪"),
        Map.entry("nvdash", "⊬"), Map.entry("nvDash", "⊭"),
        Map.entry("forall", "∀"), Map.entry("exists", "∃"), Map.entry("nexists", "∄"),
        Map.entry("neg", "¬"), Map.entry("land", "∧"), Map.entry("wedge", "∧"),
        Map.entry("lor", "∨"), Map.entry("vee", "∨"), Map.entry("to", "→"),
        Map.entry("rightarrow", "→"), Map.entry("longrightarrow", "→"),
        Map.entry("leftarrow", "←"), Map.entry("longleftarrow", "←"), Map.entry("gets", "←"),
        Map.entry("leftrightarrow", "↔"), Map.entry("longleftrightarrow", "↔"),
        Map.entry("hookleftarrow", "↩"), Map.entry("hookrightarrow", "↪"),
        Map.entry("twoheadleftarrow", "↞"), Map.entry("twoheadrightarrow", "↠"),
        Map.entry("leftharpoonup", "↼"), Map.entry("leftharpoondown", "↽"),
        Map.entry("rightharpoonup", "⇀"), Map.entry("rightharpoondown", "⇁"),
        Map.entry("uparrow", "↑"), Map.entry("downarrow", "↓"), Map.entry("updownarrow", "↕"),
        Map.entry("Uparrow", "⇑"), Map.entry("Downarrow", "⇓"), Map.entry("Updownarrow", "⇕"),
        Map.entry("mapsto", "↦"), Map.entry("longmapsto", "⟼"), Map.entry("implies", "⟹"),
        Map.entry("impliedby", "⟸"), Map.entry("iff", "⟺"), Map.entry("rightsquigarrow", "⇝"),
        Map.entry("leadsto", "⇝"), Map.entry("nearrow", "↗"), Map.entry("searrow", "↘"),
        Map.entry("swarrow", "↙"), Map.entry("nwarrow", "↖"),
        Map.entry("sum", "∑"), Map.entry("prod", "∏"), Map.entry("coprod", "∐"),
        Map.entry("int", "∫"), Map.entry("iint", "∬"), Map.entry("iiint", "∭"),
        Map.entry("oint", "∮"), Map.entry("nabla", "∇"), Map.entry("partial", "∂"),
        Map.entry("infty", "∞"), Map.entry("emptyset", "∅"), Map.entry("varnothing", "∅"),
        Map.entry("aleph", "ℵ"), Map.entry("hbar", "ℏ"), Map.entry("ell", "ℓ"),
        Map.entry("Re", "ℜ"), Map.entry("Im", "ℑ"), Map.entry("wp", "℘"),
        Map.entry("angle", "∠"), Map.entry("triangle", "△"), Map.entry("square", "□"),
        Map.entry("diamond", "⋄"), Map.entry("clubsuit", "♣"), Map.entry("diamondsuit", "♢"),
        Map.entry("heartsuit", "♡"), Map.entry("spadesuit", "♠"),
        Map.entry("prime", "′"), Map.entry("degree", "°"), Map.entry("ldots", "…"),
        Map.entry("cdots", "⋯"), Map.entry("vdots", "⋮"), Map.entry("ddots", "⋱"),
        Map.entry("dots", "…"), Map.entry("colon", ":"), Map.entry("langle", "⟨"),
        Map.entry("rangle", "⟩"), Map.entry("lfloor", "⌊"), Map.entry("rfloor", "⌋"),
        Map.entry("lceil", "⌈"), Map.entry("rceil", "⌉"), Map.entry("lbrace", "{"),
        Map.entry("rbrace", "}"), Map.entry("vert", "|"), Map.entry("Vert", "‖"),
        Map.entry("backslash", "\\"), Map.entry("checkmark", "✓"),
        Map.entry("S", "§"), Map.entry("P", "¶"), Map.entry("copyright", "©"),
        Map.entry("pounds", "£"), Map.entry("euro", "€"), Map.entry("yen", "¥"));

    /** 需要空格的“命名操作符”（pi NAMED_OPERATORS）。 */
    public static final Set<String> NAMED_OPERATORS = Set.of(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "coth", "log", "ln", "lg", "exp", "det", "dim",
        "lim", "liminf", "limsup", "max", "min", "sup", "inf", "arg", "ker",
        "deg", "gcd", "hom", "Pr", "mod");

    /** 上下限操作符（pi LIMIT_OPERATORS）。 */
    public static final Set<String> LIMIT_OPERATORS = Set.of(
        "lim", "liminf", "limsup", "max", "min", "sup", "inf", "sum", "prod",
        "coprod", "bigcap", "bigcup", "bigwedge", "bigvee", "bigsqcup",
        "biguplus", "bigoplus", "bigotimes", "bigodot", "int", "iint", "iiint", "oint");

    /** 关系符（pi RELATION_COMMANDS；两侧加空格）。 */
    public static final Set<String> RELATION_COMMANDS = Set.of(
        "le", "leq", "leqslant", "ge", "geq", "geqslant", "ne", "neq", "equiv",
        "approx", "sim", "simeq", "cong", "asymp", "doteq", "propto", "ll", "gg",
        "in", "notin", "ni", "subset", "supset", "subseteq", "supseteq",
        "sqsubset", "sqsupset", "sqsubseteq", "sqsupseteq", "prec", "preceq",
        "succ", "succeq", "parallel", "perp", "mid", "vdash", "dashv", "models",
        "Vdash", "Vvdash", "nvdash", "nvDash", "to", "rightarrow", "leftarrow",
        "leftrightarrow", "longrightarrow", "longleftarrow", "longleftrightarrow",
        "gets", "mapsto", "implies", "impliedby", "iff");

    /** 命令效果为“删除”（pi IGNORED_COMMANDS 子集）。 */
    public static final Set<String> IGNORED = Set.of(
        "left", "right", "big", "Big", "bigg", "Bigg", "textstyle", "displaystyle",
        "scriptstyle", "scriptscriptstyle", "limits", "nolimits", "hfill", "quad",
        "qquad", "mathstrut", "strut", "nonumber", "notag");

    /** 字号命令（无输出）。 */
    public static final Set<String> SIZE_COMMANDS = Set.of(
        "tiny", "scriptsize", "footnotesize", "small", "normalsize", "large",
        "Large", "LARGE", "huge", "Huge");

    /** 字体切换命令（无输出，内容照排）。 */
    public static final Set<String> FONT_SWITCHES = Set.of(
        "bf", "cal", "it", "rm", "sf", "sl", "tt", "mathbf", "mathrm", "mathit",
        "mathcal", "mathbb", "mathsf", "mathtt", "boldsymbol", "operatorname", "text");

    /** 重音命令 → 组合字符。 */
    public static final Map<String, String> ACCENTS = Map.ofEntries(
        Map.entry("hat", "\u0302"), Map.entry("widehat", "\u0302"),
        Map.entry("bar", "\u0304"), Map.entry("overline", "\u0305"),
        Map.entry("vec", "\u20d7"), Map.entry("dot", "\u0307"),
        Map.entry("ddot", "\u0308"), Map.entry("tilde", "\u0303"),
        Map.entry("widetilde", "\u0303"), Map.entry("acute", "\u0301"),
        Map.entry("grave", "\u0300"), Map.entry("breve", "\u0306"),
        Map.entry("check", "\u030c"), Map.entry("mathring", "\u030a"));

    /** 上标 Unicode 映射。 */
    public static final Map<Character, Character> SUPERSCRIPTS = Map.ofEntries(
        Map.entry('0', '⁰'), Map.entry('1', '¹'), Map.entry('2', '²'),
        Map.entry('3', '³'), Map.entry('4', '⁴'), Map.entry('5', '⁵'),
        Map.entry('6', '⁶'), Map.entry('7', '⁷'), Map.entry('8', '⁸'),
        Map.entry('9', '⁹'), Map.entry('+', '⁺'), Map.entry('-', '⁻'),
        Map.entry('=', '⁼'), Map.entry('(', '⁽'), Map.entry(')', '⁾'),
        Map.entry('a', 'ᵃ'), Map.entry('b', 'ᵇ'), Map.entry('c', 'ᶜ'),
        Map.entry('d', 'ᵈ'), Map.entry('e', 'ᵉ'), Map.entry('f', 'ᶠ'),
        Map.entry('g', 'ᵍ'), Map.entry('h', 'ʰ'), Map.entry('i', 'ⁱ'),
        Map.entry('j', 'ʲ'), Map.entry('k', 'ᵏ'), Map.entry('l', 'ˡ'),
        Map.entry('m', 'ᵐ'), Map.entry('n', 'ⁿ'), Map.entry('o', 'ᵒ'),
        Map.entry('p', 'ᵖ'), Map.entry('r', 'ʳ'), Map.entry('s', 'ˢ'),
        Map.entry('t', 'ᵗ'), Map.entry('u', 'ᵘ'), Map.entry('v', 'ᵛ'),
        Map.entry('w', 'ʷ'), Map.entry('x', 'ˣ'), Map.entry('y', 'ʸ'),
        Map.entry('z', 'ᶻ'));

    /** 下标 Unicode 映射。 */
    public static final Map<Character, Character> SUBSCRIPTS = Map.ofEntries(
        Map.entry('0', '₀'), Map.entry('1', '₁'), Map.entry('2', '₂'),
        Map.entry('3', '₃'), Map.entry('4', '₄'), Map.entry('5', '₅'),
        Map.entry('6', '₆'), Map.entry('7', '₇'), Map.entry('8', '₈'),
        Map.entry('9', '₉'), Map.entry('+', '₊'), Map.entry('-', '₋'),
        Map.entry('=', '₌'), Map.entry('(', '₍'), Map.entry(')', '₎'),
        Map.entry('a', 'ₐ'), Map.entry('e', 'ₑ'), Map.entry('h', 'ₕ'),
        Map.entry('i', 'ᵢ'), Map.entry('j', 'ⱼ'), Map.entry('k', 'ₖ'),
        Map.entry('l', 'ₗ'), Map.entry('m', 'ₘ'), Map.entry('n', 'ₙ'),
        Map.entry('o', 'ₒ'), Map.entry('p', 'ₚ'), Map.entry('r', 'ᵣ'),
        Map.entry('s', 'ₛ'), Map.entry('t', 'ₜ'), Map.entry('u', 'ᵤ'),
        Map.entry('v', 'ᵥ'), Map.entry('x', 'ₓ'));

    /** 间距命令（输出一个空格）。 */
    public static final Set<String> SPACING = Set.of(
        ",", ";", ":", "!", " ", "enspace", "thinspace", "medspace", "thickspace",
        "negthinspace", "negmedspace", "negthickspace", "quad", "qquad");
}
