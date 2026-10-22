#!/bin/sh
# Regenerates the AI model hub's API javadocs (packages com.sandy.sconsole.ai,
# com.sandy.sconsole.ai.config and com.sandy.sconsole.ai.ui; ai.internal is
# not API) into javadocs/,
# plus a classic three-pane view, javadocs/frames.html (javadoc dropped its
# own frames in JDK 12): packages top left, the chosen package's types
# bottom left, the pages on the right.
# Run from anywhere: docs/ai-model-hub-docs/generate-javadocs.sh
set -e
DOCS=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$DOCS/../.." && pwd)
OUT="$DOCS/javadocs"
CP_FILE=$(mktemp)
trap 'rm -f "$CP_FILE"' EXIT

cd "$ROOT"
mvn -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
rm -rf "$OUT"
javadoc -quiet -Xdoclint:none -public \
    -sourcepath src/main/java \
    -classpath "$(cat "$CP_FILE")" \
    -d "$OUT" \
    -doctitle "AI Model Hub API" -windowtitle "AI Model Hub API" \
    -link https://docs.oracle.com/en/java/javase/17/docs/api/ \
    com.sandy.sconsole.ai com.sandy.sconsole.ai.config com.sandy.sconsole.ai.ui

# ---- frames -----------------------------------------------------------------

# The opening of a list page, titled $1.
page_head() {
    cat <<EOF
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>$1</title>
<link rel="stylesheet" href="stylesheet.css">
<style>
  body { margin: 0 ; padding: 6px 10px ; }
  h2   { font-size: 1.05em ; margin: 8px 0 4px ; }
  h3   { font-size: 0.95em ; margin: 10px 0 2px ; }
  ul   { list-style: none ; margin: 0 ; padding: 0 ; }
  li   { padding: 1px 0 ; white-space: nowrap ; }
  .interface { font-style: italic ; }
</style>
</head><body>
EOF
}

# The kind of the type documented by page $1, from its title.
kind_of() {
    sed -n 's/.*<h1 title="\([A-Za-z ]*\) [^"]*".*/\1/p' "$1" | head -1
}

# The types of package $1, under heading $2 when they are of kind $3 (a
# class only counts under "Class" if its name doesn't end in Exception).
type_group() {
    pkg=$1 heading=$2 want=$3 dir=$(echo "$1" | tr . /) items=""
    for f in $(ls "$OUT/$dir"/*.html | sort -f) ; do
        name=$(basename "$f" .html)
        case "$name" in package-*) continue ;; esac
        kind=$(kind_of "$f")
        case "$kind:$name" in
            Class:*Exception) kind=Exception ;;
            "Enum Class":*)   kind=Enum ;;
            "Record Class":*) kind=Record ;;
        esac
        [ "$kind" = "$want" ] || continue
        css="" ; [ "$kind" = Interface ] && css=' class="interface"'
        items="$items<li><a href=\"$dir/$name.html\" target=\"main\"$css>$name</a></li>
"
    done
    [ -n "$items" ] && printf '<h3>%s</h3>\n<ul>\n%s</ul>\n' "$heading" "$items"
    return 0
}

# The type list page of package $1, or of every package when $1 is empty.
type_page() {
    if [ -n "$1" ] ; then pkgs=$1 ; title=$1
    else pkgs=$(cat "$OUT/element-list") ; title="All types"
    fi
    page_head "$title"
    printf '<h2><a href="%s" target="main">%s</a></h2>\n' \
        "$( [ -n "$1" ] && echo "$(echo "$1" | tr . /)/package-summary.html" \
                        || echo allclasses-index.html )" "$title"
    for p in $pkgs ; do
        [ -z "$1" ] && printf '<h3 style="margin-top:14px">%s</h3>\n' "$p"
        type_group "$p" Interfaces Interface
        type_group "$p" Classes    Class
        type_group "$p" Records    Record
        type_group "$p" Enums      Enum
        type_group "$p" Exceptions Exception
    done
    echo "</body></html>"
}

{
    page_head "Packages"
    echo '<h2><a href="index.html" target="main">AI Model Hub API</a></h2>'
    echo '<ul><li><a href="frame-types-all.html" target="types">All types</a></li></ul>'
    echo '<h3>Packages</h3><ul>'
    for p in $(cat "$OUT/element-list") ; do
        echo "<li><a href=\"frame-types-$p.html\" target=\"types\"" \
             "onclick=\"parent.main.location='$(echo "$p" | tr . /)/package-summary.html'\">$p</a></li>"
    done
    echo '</ul></body></html>'
} > "$OUT/frame-packages.html"

type_page "" > "$OUT/frame-types-all.html"
for p in $(cat "$OUT/element-list") ; do
    type_page "$p" > "$OUT/frame-types-$p.html"
done

cat > "$OUT/frames.html" <<'EOF'
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>AI Model Hub API</title>
<style>
  html, body { height: 100% ; margin: 0 ; }
  body  { display: flex ; }
  #left { display: flex ; flex-direction: column ; width: 22% ;
          min-width: 180px ; resize: horizontal ; overflow: hidden ;
          border-right: 1px solid #ccc ; }
  #packages { height: 28% ; border-bottom: 1px solid #ccc ; }
  #types    { flex: 1 ; }
  iframe { border: 0 ; width: 100% ; display: block ; }
  #main  { flex: 1 ; height: 100% ; }
</style>
</head><body>
<div id="left">
  <iframe id="packages" name="packages" src="frame-packages.html"></iframe>
  <iframe id="types" name="types" src="frame-types-all.html"></iframe>
</div>
<iframe id="main" name="main" src="index.html"></iframe>
</body></html>
EOF

echo "Javadocs written to $OUT (framed view: $OUT/frames.html)"
