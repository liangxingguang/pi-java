package com.pijava.coding.agent.pkg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;

/**
 * npm {@code semver} 包六件（{@code valid/validRange/satisfies/gt/compare/
 * rcompare/maxSatisfying}）的 semver4j 薄适配（批 18 裁决⑥：引库替代自写）。
 * NPM 模式放宽（{@code v1.2.3}、{@code 1.2} 均合法，同 npm-semver）。
 *
 * <p>npm-semver 的失败语义是「返回 null/false 而非抛」——本适配统一把
 * {@link SemverException} 翻成同语义，调用方永不接异常。</p>
 */
public final class Semvers {

    private Semvers() {}

    /** npm {@code valid()}：合法 ⇒ 版本串（原样），非法/空 ⇒ {@code null}。 */
    public static String valid(String version) {
        if (version == null || version.isEmpty()) {
            return null;
        }
        try {
            new Semver(version, Semver.SemverType.NPM);
            return version;
        } catch (SemverException e) {
            return null;
        }
    }

    /** npm {@code validRange()}：range 可解析 ⇒ 原样返回，否则 {@code null}。
     *  semver4j 无独立 range 解析入口 ⇒ 用 dummy 版本探针（非法 range 在
     *  satisfies 解析期抛）。 */
    public static String validRange(String range) {
        if (range == null || range.isEmpty()) {
            return null;
        }
        try {
            new Semver("0.0.0", Semver.SemverType.NPM).satisfies(range);
            return range;
        } catch (SemverException e) {
            return null;
        }
    }

    /** npm {@code satisfies(version, range)}：版本或 range 非法 ⇒ false（不抛）。 */
    public static boolean satisfies(String version, String range) {
        if (version == null || range == null) {
            return false;
        }
        try {
            return new Semver(version, Semver.SemverType.NPM).satisfies(range);
        } catch (SemverException e) {
            return false;
        }
    }

    /** npm {@code compare(a, b)}：semver §11 序（含 prerelease）。 */
    public static int compare(String a, String b) {
        return new Semver(a, Semver.SemverType.NPM)
            .compareTo(new Semver(b, Semver.SemverType.NPM));
    }

    /** npm {@code gt(a, b)}。 */
    public static boolean gt(String a, String b) {
        return compare(a, b) > 0;
    }

    /** npm {@code rcompare}：降序比较器。 */
    public static Comparator<String> rcompare() {
        return (a, b) -> compare(b, a);
    }

    /** npm {@code maxSatisfying(versions, range)}：满足 range 的最高版；
     *  无满足 ⇒ {@code null}。非法版本项被跳过（npm 同）。 */
    public static String maxSatisfying(Collection<String> versions, String range) {
        String best = null;
        for (var candidate : versions) {
            if (candidate == null || candidate.isEmpty() || !satisfies(candidate, range)) {
                continue;
            }
            if (best == null || gt(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    /** npm {@code [...versions].sort(rcompare)}：降序排序拷贝（非法项排尾）。 */
    public static List<String> sortRcompare(Collection<String> versions) {
        var sorted = new ArrayList<>(versions);
        sorted.sort((a, b) -> {
            var va = valid(a);
            var vb = valid(b);
            if (va == null && vb == null) {
                return 0;
            }
            if (va == null) {
                return 1;
            }
            if (vb == null) {
                return -1;
            }
            return compare(b, a);
        });
        return sorted;
    }
}
