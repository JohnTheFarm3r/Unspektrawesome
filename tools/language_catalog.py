import argparse
import json
import re
from pathlib import Path


def string_literals(source):
    values = []

    def scan(start, stop=None):
        i = start
        while i < len(source):
            if stop and source[i] == stop:
                return i + 1
            if source.startswith('//', i):
                end = source.find('\n', i)
                i = len(source) if end < 0 else end + 1
            elif source.startswith('/*', i):
                end = source.find('*/', i + 2)
                i = len(source) if end < 0 else end + 2
            elif source[i] == "'":
                i += 1
                while i < len(source):
                    if source[i] == '\\':
                        i += 2
                    elif source[i] == "'":
                        i += 1
                        break
                    else:
                        i += 1
            elif source[i] == '"':
                i = literal(i)
            elif source[i] == '{':
                i = scan(i + 1, '}')
            else:
                i += 1
        return i

    def literal(start):
        triple = source.startswith('"""', start)
        opening = 3 if triple else 1
        i = start + opening
        text = []
        expressions = {}
        while i < len(source):
            if source.startswith('"' * opening, i):
                end = i + opening
                values.append((start, end, ''.join(text), expressions))
                return end
            if not triple and source[i] == '\\' and i + 1 < len(source):
                escape = source[i + 1]
                if escape == 'u' and re.fullmatch('[0-9a-fA-F]{4}', source[i + 2:i + 6]):
                    text.append(chr(int(source[i + 2:i + 6], 16)))
                    i += 6
                else:
                    text.append({'n': '\n', 'r': '\r', 't': '\t', 'b': '\b', 'f': '\f'}.get(escape, escape))
                    i += 2
            elif source.startswith('${', i):
                end = scan(i + 2, '}')
                expression = source[i + 2:end - 1].strip()
                name = slot(expression, expressions)
                text.append('{' + name + '}')
                i = end
            elif source[i] == '$' and re.match('[A-Za-z_]', source[i + 1:i + 2]):
                name = re.match(r'[A-Za-z_][A-Za-z0-9_]*', source[i + 1:]).group()
                text.append('{' + slot(name, expressions) + '}')
                i += len(name) + 1
            else:
                text.append(source[i])
                i += 1
        raise ValueError(f'Unclosed string at offset {start}')

    scan(0)
    return sorted(values)


def slot(expression, known):
    simple = re.fullmatch(r'(?:[A-Za-z_][A-Za-z0-9_]*\.)*([A-Za-z_][A-Za-z0-9_]*)', expression)
    base = simple.group(1) if simple else f'value{len(known) + 1}'
    base = base if re.match('[A-Za-z]', base) else f'value{len(known) + 1}'
    name = base
    while name in known and known[name] != expression:
        name += '_2'
    known[name] = expression
    return name


def visible(value, source, start, path):
    if not value.strip() or not re.search('[A-Za-z]', value):
        return False
    if re.fullmatch(r'[dMyHhmEsS:/,. ·-]{4,}', value) or re.search(r'\bDESC\b', value):
        return False
    if value == '{flag}  {nativeName}':
        return False
    if path.name == 'InternalLogRecorder.kt' and not value.startswith((
            'Log storage unavailable:', 'Automatic logs unavailable:', 'Native log capture interrupted:', 'Unable to open the selected file',
            'Share diagnostic report')):
        return False
    prefix = source[max(0, start - 240):start]
    line = source[source.rfind('\n', 0, start) + 1:start]
    # Storage, diagnostics and machine identifiers do not belong in a language file.
    blocked = r'(?:testTag|Regex|Regex\.escape|Color|File|loadLibrary|getSharedPreferences|putExtra|getStringExtra|setAction|setClassName|setData|setRequestProperty|encodeToString|decode|System\.getProperty|Class\.forName|forName|getDeclaredField|getDeclaredMethod|register|setName|executeShellCommand|nativeLibraryDir|Suppress|SuppressLint|SuppressWarnings|removeSuffix|removePrefix|startsWith|endsWith)\s*\(\s*$'
    if re.search(blocked, prefix):
        return False
    if re.search(r'\btag\s*=\s*$', line):
        return False
    if re.search(r'(?:InternalLogRecorder|Log)\.[A-Za-z]+\s*\([^\n]*$', prefix):
        return False
    if path.name == 'LanguagePack.kt' and re.search(r'\.put\("instructions",\s*$', prefix):
        return False
    if re.search(r'\b(?:Text|(?:AppLanguage|language)\.(?:text|format)|SettingsCard|SettingsHint|SettingsChoice|Eyebrow|Body|Hint|GalleryAction|CameraOverlayAction)\(\s*(?:(?:text|label|title)\s*=\s*)?$', prefix):
        return True
    if re.search(r'\blabel\s*=\s*$', line) and re.search(r'(?:animate\w*|rememberInfiniteTransition)\s*\([^;{}]*$', prefix):
        return False
    if prefix.rstrip().endswith('['):
        return False
    if any(mark in value for mark in ['\\p{', '\\s', 'CREATE TABLE', 'SELECT ', 'INSERT ', 'ALTER TABLE', 'UPDATE ', 'DELETE FROM ', 'PRAGMA ']):
        return False
    if re.match(r'^(?:[a-z]+://|image/|application/|text/|DCIM/|android\.|com\.|VK_|std::)', value):
        return False
    if re.search(r'\b[a-z][A-Za-z_]*=(?:\{|%)', value) or re.search(r' = (?:\?|\d+|\{[A-Z_]+})|\b(?:IN|AND)\s*\(', value):
        return False
    if re.match(r'^\{(?:packageName|value1)}(?:\.|:gallery|-gallery)', value) or value.startswith('Unspektrawesome/'):
        return False
    if '\u0000' in value or re.fullmatch(r'[\w%{}./\\-]+\.(?:json|zip|dcp|jpg|png|dng|raw|rgba|spektra|bin|tmp|spv|vert|comp|xml|log|shot|backup|apk|\{extension})', value, re.I):
        return False
    if path.name == 'PanoramaPng.kt' and value in {'IHDR', 'IDAT', 'IEND', 'sRGB'}:
        return False
    if value in {'-testing', '.tmp', '0x', 'User-Agent', 'X-GitHub-Api-Version', 'JohnTheFarm3r/Unspektrawesome-Releases', 'Custom profiles.unspektrawesome.json'}:
        return False
    if re.fullmatch(r'[dMyHhmEsS:/,. -]{4,}', value) or re.match(r'^[a-z]+:[^ ]', value):
        return False
    if ' ' not in value and ('_' in re.sub(r'\{[^}]+}', '', value)):
        return False
    plain = re.sub(r'\{[^}]+}', '', value).strip()
    if not re.search('[A-Za-z]', plain):
        return bool(re.search(r'(?:Text|(?:AppLanguage|language)\.format)\(\s*(?:text\s*=\s*)?$', prefix) and re.search(r'\{[a-zA-Z][a-zA-Z0-9_]*}', value))
    if re.search(r'[a-z][A-Z]', plain) and ' ' not in plain and path.name not in LABEL_FILES:
        return False
    if re.match(r'\s*(?:override\s+)?(?:private\s+)?(?:const\s+)?val\s+[A-Z_]+\s*=\s*$', line) and any(x in line for x in ['ACTION', 'CHANNEL', 'EXTRA', 'PREFIX', 'KEY', 'FORMAT', 'FILE', 'DIRECTORY', 'TAG']):
        return False
    if plain[0].islower() and ' ' not in value.strip() and plain not in LOWERCASE_LABELS:
        return False
    return True


LABEL_FILES = {'SpektraFilmParams.kt', 'SpektraLook.kt', 'PhotoInformation.kt', 'ProcessingPriority.kt',
               'RawLevelCalibration.kt', 'VolumeButtonAction.kt', 'AeSettings.kt', 'PhotoCrop.kt', 'VendorOverrides.kt',
               'LensRole.java', 'LensFacing.java', 'FlashMode.java', 'CameraPreferences.kt', 'CameraDescriptor.java'}
LOWERCASE_LABELS = {'lens', 'lenses', 'profile', 'profiles', 'stream', 'streams', 'photo', 'photos', 'second', 'seconds',
                    'on', 'off', 'auto', 'enabled', 'disabled', 'mm', 'px', 'fps', 's', 'i32', 'i64', 'u8', 'float',
                    'i32[]', 'i64[]', 'u8[]', 'float[]', 'waiting', 'available', 'selected', 'live', 'm', 'second…', 'seconds…'}
OMIT_FILES = {'ImageDiagnostics.kt', 'DiagnosticReport.kt', 'RollingLogStore.kt', 'PublicDiagnosticLogs.kt',
              'CaptureIdentity.kt', 'CaptureMetadata.kt', 'VideoMetadata.kt', 'Mp4Metadata.kt', 'SpektraStateJson.kt', 'LensDiscoveryStore.kt',
              'EmbeddedSettings.kt', 'JpegProfilePayload.kt',
              'TelemetryCamera.kt', 'TelemetryGeometry.kt', 'TelemetryCalibration.kt', 'TelemetryCameraCompatibility.kt', 'TelemetryRecorder.kt', 'TelemetryMp4.kt', 'PcmAudioTrack.kt', 'TelemetryPackets.kt', 'StabilizationCapture.java',
              'LanguageResetActivity.kt', 'LanguageRecovery.kt', 'LanguageText.kt', 'BuiltInLanguage.kt'}


def log_spans(source, literals):
    ends = {start: end for start, end, _, _ in literals}
    spans = []
    for match in re.finditer(r'\b(?:InternalLogRecorder|Log)\.[A-Za-z]+\s*\(', source):
        depth, i = 1, match.end()
        while i < len(source) and depth:
            if i in ends:
                i = ends[i]
                continue
            if source[i] == '(':
                depth += 1
            elif source[i] == ')':
                depth -= 1
            i += 1
        spans.append((match.start(), i))
    return spans


def catalog(source_dir):
    entries = set()
    locations = {}
    for path in sorted(source_dir.rglob('*')):
        if path.suffix not in {'.kt', '.java'} or path.name in OMIT_FILES:
            continue
        source = path.read_text(encoding='utf-8')
        literals = string_literals(source)
        spans = log_spans(source, literals)
        literals = [entry for entry in literals if not any(a <= entry[0] < b for a, b in spans)]
        for start, end, value, expressions in literals:
            if not visible(value, source, start, path):
                continue
            formatted = value
            argument = 0

            def replace_format(match):
                nonlocal argument
                argument += 1
                return '{value' + str(argument) + '}'

            formatted = re.sub(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfeg]', replace_format, formatted)
            if re.fullmatch(r'%[0-9.]*[a-z]', formatted):
                continue
            entries.add(formatted)
            locations.setdefault(formatted, []).append(f'{path.name}:{source.count(chr(10), 0, start) + 1}')
        # Literal concatenations form a single sentence, which translators may reorder.
        for index, (start, end, value, expressions) in enumerate(literals):
            combined = value
            arguments = dict(expressions)
            current_end = end
            for next_start, next_end, next_value, next_expressions in literals[index + 1:]:
                if next_start < current_end:
                    continue
                if not re.fullmatch(r'\s*\+\s*', source[current_end:next_start]):
                    break
                replacements = {name: slot(expression, arguments) for name, expression in next_expressions.items()}
                next_value = re.sub(r'\{([A-Za-z][A-Za-z0-9_]*)}', lambda m: '{' + replacements.get(m[1], m[1]) + '}', next_value)
                combined += next_value
                current_end = next_end
                if visible(combined, source, start, path):
                    entries.add(combined)
                    locations.setdefault(combined, []).append(f'{path.name}:{source.count(chr(10), 0, start) + 1}')
        if path.name == 'SpektraFilmParams.kt':
            for match in re.finditer(r'val (?:FILM|PAPER)_LABELS = listOf\((.*?)\n\s*\)', source, re.S):
                for _, _, label, _ in string_literals(match[1]):
                    brand, _, name = label.partition(' ')
                    entries.update([brand, name])
                    title = re.match(r'^(.*) (\d+[A-Z]?)(.*)$', name)
                    if title:
                        entries.update([title[1], title[2] + title[3]])
                    if ' ' in name:
                        entries.update(name.rsplit(' ', 1))
        if path.name == 'SpektraLook.kt':
            match = re.search(r'val formats = listOf\((.*?)\n\s*\)', source, re.S)
            if match:
                entries.update(label.rsplit(' ', 1)[0] for _, _, label, _ in string_literals(match[1]))
    entries.update(['FILM', 'PRINT', 'FORMAT', 'EFFECTS', 'EXPOSURE', 'CUSTOM', 'RAW_SENSOR', 'RAW10', 'RAW12'])
    return dict.fromkeys(sorted(entries)), locations


def native_messages(source_dir):
    entries = set()
    if source_dir is None:
        return entries
    for path in source_dir.rglob('*.cpp'):
        if path.name in {'NativeDiagnostics.cpp', 'VulkanDiagnostics.cpp', 'VulkanBridge.cpp', 'RawFrameCompression.cpp'}:
            continue
        source = path.read_text(encoding='utf-8')
        for match in re.finditer(r'(?:runtime_error\s*\(|(?:lastError|errorMessage)\s*=\s*)\s*"((?:[^"\\]|\\.)*)"', source):
            value = string_literals('"' + match[1] + '"')[0][2]
            if visible(value, '', 0, path) and not value.startswith('Injected failure'):
                entries.add(value)
    return entries


BUNDLED_LANGUAGES = {
    'de': 'Deutsch', 'fr': 'Français', 'it': 'Italiano', 'ru': 'Русский', 'es': 'Español',
    'pt': 'Português', 'uk': 'Українська', 'pl': 'Polski', 'zh': '简体中文', 'ja': '日本語',
    'sr': 'Srpski (latinica)', 'hu': 'Magyar',
}
PLACEHOLDER = re.compile(r'\{([A-Za-z][A-Za-z0-9_]*|[0-9]+)\}')


def validate_translation(body, english, name):
    if body.get('language') != name or body.get('direction') != 'ltr' or body.get('format') != 'unspektrawesome.language' or body.get('version') != 1:
        raise ValueError(f'{name}: invalid language metadata')
    strings = body.get('strings', {})
    missing, stale = set(english) - set(strings), set(strings) - set(english)
    if missing or stale:
        raise ValueError(f'{name}: {len(missing)} missing and {len(stale)} stale entries. Missing: {sorted(missing)[:3]!r}; stale: {sorted(stale)[:3]!r}')
    identical = set()
    for key, value in strings.items():
        if not isinstance(value, str) or not value.strip() or len(value) > 32768 or '\x00' in value:
            raise ValueError(f'{name}: invalid translation: {key!r}')
        if set(PLACEHOLDER.findall(key)) != set(PLACEHOLDER.findall(value)):
            raise ValueError(f'{name}: changed placeholders: {key!r}')
        if '\u2014' in value or 'ZXQ' in value or re.search(r'\[\[\s*[78][0-9]{4}', value):
            raise ValueError(f'{name}: invalid punctuation or unfinished text: {key!r}')
        if name == 'Srpski (latinica)' and re.search('[\u0400-\u04ff]', value):
            raise ValueError(f'{name}: use Latin script: {key!r}')
        if value == key:
            identical.add(key)
    if identical != set(body.get('unchanged', [])):
        raise ValueError(f'{name}: review identical translations and update the unchanged list: {sorted(identical ^ set(body.get("unchanged", [])))[:5]!r}')
    return {key: body[key] for key in ['format', 'version', 'language', 'direction', 'strings']}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--inventory', type=Path)
    parser.add_argument('--native', type=Path)
    parser.add_argument('--translations', type=Path)
    args = parser.parse_args()
    entries, locations = catalog(args.source)
    entries.update(dict.fromkeys(sorted(native_messages(args.native))))
    translations = {}
    if args.translations:
        for code, name in BUNDLED_LANGUAGES.items():
            translations[code] = validate_translation(json.loads((args.translations / f'{code}.json').read_text(encoding='utf-8')), entries, name)
    body = {'format': 'unspektrawesome.language', 'version': 1, 'language': 'English', 'direction': 'ltr',
            'strings': {key: key for key in entries}}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(body, ensure_ascii=False, indent=2) + '\n'
    if not args.output.exists() or args.output.read_text(encoding='utf-8') != encoded:
        args.output.write_text(encoded, encoding='utf-8')
    if args.inventory:
        args.inventory.write_text(json.dumps(locations, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'English language template: {len(entries)} entries')
    for code, body in translations.items():
        output = args.output.parent / f'{code}.json'
        encoded = json.dumps(body, ensure_ascii=False, indent=2) + '\n'
        if not output.exists() or output.read_text(encoding='utf-8') != encoded:
            output.write_text(encoded, encoding='utf-8')
    if translations:
        print(f'Bundled languages: {len(translations)} complete catalogs verified')


if __name__ == '__main__':
    main()
