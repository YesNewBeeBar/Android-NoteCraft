#!/usr/bin/env node
/**
 * NoteCraft 静态自检。
 *
 * 这不是编译器（本机没有 JDK / Android SDK），但能挡住写这种规模工程时
 * 最容易犯、又最难靠肉眼发现的错误：
 *
 *   1. 括号未闭合
 *   2. 未闭合的字符串字面量
 *   3. 代码里混入中文标点（字符串与注释不算）
 *   4. import 了不存在的本工程符号 / 包
 *   5. 用了本工程的类型却没 import（编译必然报 unresolved reference）
 *   6. 完全限定名指向不存在的包或类型
 *   7. R.string / R.drawable / R.color 与 manifest 引用的资源是否存在
 *   8. 导入但未使用的 import（清理噪音）
 *
 * 解析器要点：字符串模板 ${...} 里的内容是代码，括号必须参与平衡，
 * 否则会误报；同时字符串与注释要抹成空格但**保留换行**，行号才准。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'app/src/main/java');
const RES = path.join(ROOT, 'app/src/main/res');

const errors = [];
const warnings = [];

function walk(dir, filter) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(full, filter));
    else if (filter(e.name)) out.push(full);
  }
  return out;
}

// ---------------------------------------------------------------- 词法清洗

function stripLiterals(text) {
  const chars = text.split('');

  const blank = (from, to) => {
    for (let k = from; k < to && k < chars.length; k++) {
      if (chars[k] !== '\n') chars[k] = ' ';
    }
  };

  const skipStringAt = (start) => {
    const quote = text[start];
    const triple = quote === '"' && text.slice(start, start + 3) === '"""';
    let i = start + (triple ? 3 : 1);
    const n = text.length;
    while (i < n) {
      if (!triple && text[i] === '\\') { i += 2; continue; }
      if (triple && text.slice(i, i + 3) === '"""') return i + 3;
      if (!triple && text[i] === quote) return i + 1;
      i++;
    }
    return -1;
  };

  const findMatchingBrace = (openIndex) => {
    let depth = 0;
    let i = openIndex;
    const n = text.length;
    while (i < n) {
      const c = text[i];
      if (c === '/' && text[i + 1] === '/') { while (i < n && text[i] !== '\n') i++; continue; }
      if (c === '/' && text[i + 1] === '*') {
        i += 2;
        while (i < n && !(text[i] === '*' && text[i + 1] === '/')) i++;
        i += 2;
        continue;
      }
      if (c === '"' || c === "'") {
        const next = skipStringAt(i);
        if (next < 0) return -1;
        i = next;
        continue;
      }
      if (c === '{') depth++;
      else if (c === '}') {
        depth--;
        if (depth === 0) return i;
      }
      i++;
    }
    return -1;
  };

  let i = 0;
  const n = text.length;
  while (i < n) {
    const c = text[i];
    const next = text[i + 1];

    if (c === '/' && next === '/') {
      const s = i;
      while (i < n && text[i] !== '\n') i++;
      blank(s, i);
      continue;
    }

    if (c === '/' && next === '*') {
      const s = i;
      i += 2;
      while (i < n && !(text[i] === '*' && text[i + 1] === '/')) i++;
      i = Math.min(i + 2, n);
      blank(s, i);
      continue;
    }

    if (c === '"' || c === "'") {
      const quote = c;
      const triple = quote === '"' && text.slice(i, i + 3) === '"""';
      const s = i;
      i += triple ? 3 : 1;

      while (i < n) {
        if (!triple && text[i] === '\\') { i += 2; continue; }
        if (triple && text.slice(i, i + 3) === '"""') { i += 3; break; }
        if (!triple && text[i] === quote) { i++; break; }
        if (text[i] === '\n' && quote === "'") break;
        // 字符串模板：${...} 里是真代码，括号要留给平衡检查
        if (!triple && text[i] === '$' && text[i + 1] === '{') {
          const close = findMatchingBrace(i + 1);
          if (close > 0) {
            blank(s, i);
            i = close;
            continue;
          }
        }
        i++;
      }

      if (i >= n && !triple && quote === '"') {
        const line = text.slice(0, s).split('\n').length;
        errors.push(`第 ${line} 行附近有未闭合的字符串字面量`);
      }
      blank(s, i);
      continue;
    }

    i++;
  }

  // 行还原：import / package 行保留原文，供后续 import 检查使用
  const strippedLines = chars.join('').split('\n');
  return text
    .split('\n')
    .map((original, idx) =>
      /^\s*(import|package)\b/.test(original) ? original : (strippedLines[idx] ?? '')
    )
    .join('\n');
}

// ---------------------------------------------------------------- 词法检查

const ktFiles = walk(SRC, (n) => n.endsWith('.kt'));
const codeByFile = new Map();

for (const file of ktFiles) {
  const rel = path.relative(ROOT, file);
  const raw = fs.readFileSync(file, 'utf8');
  if (raw.charCodeAt(0) === 0xfeff) errors.push(`${rel}: 文件开头有 BOM`);

  const code = stripLiterals(raw);
  codeByFile.set(file, code);

  const stack = [];
  const pairs = { ')': '(', ']': '[', '}': '{' };
  let line = 1;
  let reported = false;
  for (let i = 0; i < code.length; i++) {
    const c = code[i];
    if (c === '\n') line++;
    if (c === '(' || c === '[' || c === '{') stack.push({ c, line });
    else if (c in pairs) {
      const top = stack.pop();
      if (!top || top.c !== pairs[c]) {
        if (!reported) {
          errors.push(
            `${rel}:${line} 括号不匹配：遇到 '${c}'，栈顶是 ` +
            (top ? `'${top.c}'(第 ${top.line} 行)` : '空')
          );
          reported = true;
        }
        break;
      }
    }
  }
  if (!reported && stack.length) {
    const top = stack[stack.length - 1];
    errors.push(`${rel}: '${top.c}' 在第 ${top.line} 行打开却没有闭合`);
  }

  code.split('\n').forEach((codeLine, idx) => {
    const bad = codeLine.match(/[，。；：！？“”‘’（）、【】《》]/g);
    if (bad) errors.push(`${rel}:${idx + 1} 代码里混入中文标点 ${[...new Set(bad)].join('')}`);
    if (codeLine.includes('\t')) warnings.push(`${rel}:${idx + 1} 使用了 Tab`);
  });
}

// ---------------------------------------------------------------- 声明索引

const DECL_RE = /^\s*(?:(?:public|internal|private|protected|open|abstract|sealed|data|enum|annotation|value|inline|suspend|operator|infix|external|const|lateinit|override|final|actual|expect|tailrec|vararg|noinline|crossinline|reified|companion)\s+)*(?:class|interface|object|fun|val|var|typealias)\s+([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)*)/;

const packageOf = new Map();
const declsByFile = new Map();
const declsByPackage = new Map();
const allMembers = new Set();

for (const file of ktFiles) {
  const raw = fs.readFileSync(file, 'utf8');
  const pkgMatch = raw.match(/^package\s+([\w.]+)/m);
  const pkg = pkgMatch ? pkgMatch[1] : '';
  packageOf.set(file, pkg);

  const topLevel = new Set();
  const members = new Set();

  for (const line of raw.split('\n')) {
    if (/^\s/.test(line)) continue;
    const m = line.match(DECL_RE);
    if (m) topLevel.add(m[1].split('.').pop());
  }
  for (const m of raw.matchAll(/\b(?:const\s+)?val\s+([A-Za-z_]\w*)/g)) members.add(m[1]);
  for (const m of raw.matchAll(/\b(?:const\s+)?val\s+([A-Z][A-Z0-9_]+)\b/g)) members.add(m[1]);
  for (const m of raw.matchAll(/^\s{4,}([A-Z][A-Z0-9_]{2,})\s*(?:[,;=]|$)/gm)) members.add(m[1]);
  // 普通成员函数/属性也要索引：FQN 检查要验证 Permissions.hasAudio 这类写法
  for (const m of raw.matchAll(/\b(?:fun|val|var)\s+([A-Za-z_]\w*)/g)) members.add(m[1]);

  declsByFile.set(file, { topLevel, members });
  if (!declsByPackage.has(pkg)) declsByPackage.set(pkg, new Set());
  topLevel.forEach((s) => declsByPackage.get(pkg).add(s));
  members.forEach((s) => allMembers.add(s));
}

const allProjectSymbols = new Set();
for (const set of declsByPackage.values()) set.forEach((s) => allProjectSymbols.add(s));

// ---------------------------------------------------------------- import 检查

const LIB_PREFIXES = [
  'android.', 'androidx.', 'java.', 'javax.', 'kotlin.', 'kotlinx.',
  'okhttp3.', 'okio.', 'org.json.', 'org.jetbrains.'
];

for (const file of ktFiles) {
  const rel = path.relative(ROOT, file);
  const raw = fs.readFileSync(file, 'utf8');
  const code = codeByFile.get(file);
  const lines = raw.split('\n');

  const imported = [];
  lines.forEach((line, idx) => {
    const m = line.match(/^import\s+([\w.]+)(?:\.\*)?\s*(?:as\s+(\w+))?\s*$/);
    if (!m) return;
    const fq = m[1];
    const alias = m[2] || fq.split('.').pop();

    if (fq.includes('.*')) {
      imported.push({ fq, alias: '*', line: idx + 1 });
      return;
    }
    if (LIB_PREFIXES.some((p) => fq.startsWith(p))) {
      imported.push({ fq, alias, line: idx + 1 });
      return;
    }
    if (fq.startsWith('com.dsh.notes.R') || fq.startsWith('com.dsh.notes.BuildConfig')) {
      imported.push({ fq, alias, line: idx + 1 });
      return;
    }

    if (!fq.startsWith('com.dsh.notes')) {
      warnings.push(`${rel}:${idx + 1} import 了未知来源的包：${fq}`);
      imported.push({ fq, alias, line: idx + 1 });
      return;
    }

    const parts = fq.split('.');
    const symbol = parts.pop();
    const pkg = parts.join('.');
    if (!declsByPackage.has(pkg)) {
      errors.push(`${rel}:${idx + 1} import 的包不存在：${pkg}`);
    } else if (!declsByPackage.get(pkg).has(symbol)) {
      warnings.push(`${rel}:${idx + 1} import ${fq}：没有顶层声明 ${symbol}（可能是扩展函数）`);
    }
    imported.push({ fq, alias, line: idx + 1 });
  });

  // 导入但未使用
  const bodyLines = code.split('\n').filter((l) => !l.trim().startsWith('import '));
  const body = bodyLines.join('\n');
  const usesDelegation = bodyLines.some((l) => /\bby\s/.test(l));
  for (const imp of imported) {
    if (imp.alias === '*' || imp.alias === 'R' || imp.alias === 'BuildConfig') continue;
    // 属性委托 by 需要 getValue/setValue，但它们不会以普通标识符形式出现
    if (usesDelegation && (imp.alias === 'getValue' || imp.alias === 'setValue')) continue;
    const escaped = imp.alias.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    if (!new RegExp(`\\b${escaped}\\b`).test(body)) {
      warnings.push(`${rel}:${imp.line} 导入但未使用：${imp.fq}`);
    }
  }
}

// ---------------------------------------------------------------- 未 import 就使用

const COMMON_TYPES = new Set([
  'String', 'Int', 'Long', 'Float', 'Double', 'Boolean', 'Short', 'Byte', 'Char', 'Unit', 'Any',
  'Nothing', 'List', 'Map', 'Set', 'MutableList', 'MutableMap', 'MutableSet', 'Array', 'Pair',
  'Triple', 'Sequence', 'Result', 'Throwable', 'Exception', 'Error', 'Regex', 'File',
  'StringBuilder', 'Comparable', 'Iterable', 'IllegalStateException', 'IllegalArgumentException',
  'RuntimeException', 'Thread', 'Math', 'System', 'Number', 'JSONObject', 'Uri', 'FileOutputStream',
  'RandomAccessFile', 'ByteBuffer', 'ByteOrder', 'Base64', 'KeyStore', 'Cipher', 'KeyGenerator',
  'SecretKey', 'GCMParameterSpec', 'Calendar', 'Date', 'Locale', 'SimpleDateFormat'
]);

for (const file of ktFiles) {
  const rel = path.relative(ROOT, file);
  const raw = fs.readFileSync(file, 'utf8');
  const code = codeByFile.get(file);
  const pkg = packageOf.get(file);
  const local = declsByFile.get(file);
  const samePkg = declsByPackage.get(pkg) || new Set();

  const importedSimple = new Set();
  let hasStar = false;
  for (const m of raw.matchAll(/^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$/gm)) {
    if (m[1].endsWith('.*')) { hasStar = true; continue; }
    importedSimple.add(m[2] || m[1].split('.').pop());
  }

  const localNames = new Set([...local.topLevel, ...local.members]);
  for (const m of code.matchAll(/\b(?:val|var|fun|class|object|interface|typealias)\s+([A-Za-z_]\w*)/g)) {
    localNames.add(m[1]);
  }

  const suspects = new Map();
  code.split('\n').forEach((line, idx) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('import ') || trimmed.startsWith('package ')) return;
    for (const m of line.matchAll(/(?:^|[^\w.])([A-Z][A-Za-z0-9_]{2,})\s*(?=[(<.:{])/g)) {
      if (!suspects.has(m[1])) suspects.set(m[1], idx + 1);
    }
    for (const m of line.matchAll(/:\s*([A-Z][A-Za-z0-9_]{2,})\b/g)) {
      if (!suspects.has(m[1])) suspects.set(m[1], idx + 1);
    }
  });

  for (const [name, lineNo] of suspects) {
    if (COMMON_TYPES.has(name)) continue;
    if (localNames.has(name)) continue;
    if (importedSimple.has(name) || hasStar) continue;
    if (!allProjectSymbols.has(name)) continue; // 第三方/框架类型，交给编译器
    if (!samePkg.has(name)) {
      errors.push(`${rel}:${lineNo} 使用了 ${name}，但既没 import 它，包 ${pkg} 里也没有`);
    }
  }
}

// ---------------------------------------------------------------- 完全限定名检查

for (const file of ktFiles) {
  const rel = path.relative(ROOT, file);
  const code = codeByFile.get(file);

  code.split('\n').forEach((line, idx) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('package ') || trimmed.startsWith('import ')) return;

    for (const m of line.matchAll(/\bcom\.dsh\.notes\.[\w.]+/g)) {
      const fq = m[0];
      const after = line.slice(m.index + fq.length);
      if (/^\.[A-Za-z_]/.test(after)) continue; // 被正则截断了，交给更长的匹配
      if (fq.startsWith('com.dsh.notes.R') || fq.startsWith('com.dsh.notes.BuildConfig')) continue;

      const parts = fq.split('.');
      const last = parts[parts.length - 1];
      const asPackage = parts.slice(0, -1).join('.');

      if (declsByPackage.has(asPackage)) {
        if (!declsByPackage.get(asPackage).has(last)) {
          errors.push(`${rel}:${idx + 1} 用了 ${fq}，但包 ${asPackage} 里没有 ${last}`);
        }
        continue;
      }

      const outerPkg = parts.slice(0, -2).join('.');
      const outerType = parts[parts.length - 2];
      if (declsByPackage.has(outerPkg) && declsByPackage.get(outerPkg).has(outerType)) {
        if (!allMembers.has(last)) {
          errors.push(`${rel}:${idx + 1} 用了 ${fq}，但 ${outerType} 里找不到成员 ${last}`);
        }
        continue;
      }

      errors.push(`${rel}:${idx + 1} 完全限定名 ${fq} 找不到对应的包或类型`);
    }
  });
}

// ---------------------------------------------------------------- 资源检查

const resStrings = new Set();
const resColors = new Set();
const resDrawables = new Set();

function collectRes(dir) {
  for (const file of walk(dir, (n) => n.endsWith('.xml'))) {
    const raw = fs.readFileSync(file, 'utf8');
    for (const m of raw.matchAll(/<string\s+name="([^"]+)"/g)) resStrings.add(m[1]);
    for (const m of raw.matchAll(/<color\s+name="([^"]+)"/g)) resColors.add(m[1]);
    resDrawables.add(path.basename(file).replace(/\.xml$/, ''));
  }
}
collectRes(path.join(RES, 'values'));
collectRes(path.join(RES, 'drawable'));
collectRes(path.join(RES, 'mipmap-anydpi-v26'));

for (const file of ktFiles) {
  const rel = path.relative(ROOT, file);
  const code = codeByFile.get(file);
  for (const m of code.matchAll(/\bR\.string\.(\w+)/g)) {
    if (!resStrings.has(m[1])) errors.push(`${rel} 引用了不存在的字符串资源 R.string.${m[1]}`);
  }
  for (const m of code.matchAll(/\bR\.drawable\.(\w+)/g)) {
    if (!resDrawables.has(m[1])) errors.push(`${rel} 引用了不存在的 drawable R.drawable.${m[1]}`);
  }
  for (const m of code.matchAll(/\bR\.color\.(\w+)/g)) {
    if (!resColors.has(m[1])) errors.push(`${rel} 引用了不存在的颜色 R.color.${m[1]}`);
  }
}

// ---------------------------------------------------------------- manifest 互查

const manifestPath = path.join(ROOT, 'app/src/main/AndroidManifest.xml');
const manifest = fs.readFileSync(manifestPath, 'utf8');

for (const m of manifest.matchAll(/android:name="\.([\w.]+)"/g)) {
  const cls = m[1].split('.').pop();
  if (!allProjectSymbols.has(cls)) {
    errors.push(`AndroidManifest.xml 引用了不存在的类 .${m[1]}`);
  }
}
for (const m of manifest.matchAll(/@(string|drawable|mipmap|color)\/(\w+)/g)) {
  const [, type, name] = m;
  const ok =
    (type === 'string' && resStrings.has(name)) ||
    ((type === 'drawable' || type === 'mipmap') && (resDrawables.has(name) || name === 'ic_launcher' || name === 'ic_launcher_round')) ||
    (type === 'color' && resColors.has(name));
  if (!ok) errors.push(`AndroidManifest.xml 引用的资源 @${type}/${name} 不存在`);
}

// ---------------------------------------------------------------- 输出

console.log(`扫描 ${ktFiles.length} 个 Kotlin 文件`);
console.log(`资源：string ${resStrings.size} / color ${resColors.size} / drawable ${resDrawables.size}`);
console.log('');

if (warnings.length) {
  console.log(`⚠️  警告 ${warnings.length} 条`);
  warnings.forEach((w) => console.log('   ' + w));
  console.log('');
}

if (errors.length) {
  console.log(`❌ 错误 ${errors.length} 条`);
  errors.forEach((e) => console.log('   ' + e));
  process.exit(1);
}
console.log('✅ 结构性检查全部通过');
