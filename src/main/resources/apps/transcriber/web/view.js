/*
 * QTranscriber's rendered view (feature-design.md §8.1). show( markdown )
 * renders a transcription:
 *
 * - maths ($...$, $$...$$) is taken out before markdown-it sees it, so its
 *   underscores and backslashes stay as written, and each formula is drawn
 *   by MathJax (with mhchem for \ce);
 * - a diagram block is a framed box with its description; a smiles: line
 *   in it is drawn under the text, or "SMILES not drawable" in red;
 * - an uncertain block is an amber box;
 * - the LCT marker is a dashed rule between the passage and the question.
 */
( function() {

  'use strict' ;

  var OPEN  = '' ;
  var CLOSE = '' ;
  var MATH  = /\\\$|\$\$([\s\S]+?)\$\$|\$([^$\n]+?)\$/g ;
  var SLOT  = new RegExp( OPEN + '(\\d+)' + CLOSE, 'g' ) ;
  var MARK  = /<p>&lt;!-- question --&gt;<\/p>/g ;

  var SVG_NS = 'http://www.w3.org/2000/svg' ;

  var md         = null ;
  var generation = 0 ;

  function escape( s ) {
    return s.replace( /&/g, '&amp;' ).replace( /</g, '&lt;' )
            .replace( />/g, '&gt;' ).replace( /"/g, '&quot;' ) ;
  }

  /** The text of a fence's lines, as a box. */
  function fence( tokens, idx, options, env, self ) {

    var token = tokens[idx] ;
    var info  = token.info.trim() ;
    var lines = token.content.replace( /\n$/, '' ).split( '\n' ) ;

    if( info === 'diagram' ) {
      var html = '<div class="diagram"><div class="label">FIGURE</div>' ;
      var text = [] ;
      lines.forEach( function( line ) {
        var s = line.trim() ;
        if( /^smiles:/i.test( s ) ) {
          var smiles = s.substring( s.indexOf( ':' ) + 1 ).trim() ;
          html += '<div>' + escape( text.join( '\n' ) )
                                .replace( /\n/g, '<br>' ) + '</div>' ;
          text  = [] ;
          html += '<div class="smiles" data-smiles="' + escape( smiles ) +
                  '"><span class="code">smiles: ' + escape( smiles ) +
                  '</span></div>' ;
        }
        else text.push( line ) ;
      } ) ;
      html += '<div>' + escape( text.join( '\n' ) ).replace( /\n/g, '<br>' ) +
              '</div></div>' ;
      return html ;
    }
    if( info === 'uncertain' ) {
      return '<div class="uncertain">' + escape( lines.join( '\n' ) ) +
             '</div>' ;
    }
    return '<pre><code>' + escape( token.content ) + '</code></pre>' ;
  }

  function renderer() {
    if( md ) return md ;
    md = window.markdownit( { html : false, linkify : false,
                              typographer : false } ) ;
    md.renderer.rules.fence = fence ;
    return md ;
  }

  /**
   * The markdown with each formula outside fences replaced by a slot,
   * and the formulas, in order.
   */
  function takeMaths( markdown ) {

    var maths   = [] ;
    var out     = [] ;
    var chunk   = [] ;
    var inFence = false ;

    function flush() {
      var text = chunk.join( '\n' ) ;
      out.push( text.replace( MATH, function( all, display, inline ) {
        if( all === '\\$' ) return all ;
        maths.push( { tex     : display !== undefined ? display : inline,
                      display : display !== undefined } ) ;
        return OPEN + ( maths.length - 1 ) + CLOSE ;
      } ) ) ;
      chunk = [] ;
    }

    markdown.split( '\n' ).forEach( function( line ) {
      if( line.trim().indexOf( '```' ) === 0 ) {
        if( !inFence ) flush() ;
        inFence = !inFence ;
        out.push( line ) ;
      }
      else if( inFence ) out.push( line ) ;
      else chunk.push( line ) ;
    } ) ;
    flush() ;
    return { markdown : out.join( '\n' ), maths : maths } ;
  }

  /** Draw each formula slot of {@code root} with MathJax. */
  function drawMaths( root, maths, gen ) {

    var slots = root.querySelectorAll( 'span.tex' ) ;
    var work  = Array.prototype.map.call( slots, function( slot ) {
      var m = maths[ +slot.getAttribute( 'data-n' ) ] ;
      return MathJax.tex2svgPromise( m.tex, { display : m.display } )
        .then( function( node ) {
          if( gen !== generation ) return ;
          slot.textContent = '' ;
          slot.appendChild( node ) ;
        } )
        .catch( function( e ) {
          if( gen !== generation ) return ;
          slot.className   = 'math-error' ;
          slot.textContent = ( m.display ? '$$' : '$' ) + m.tex +
                             ( m.display ? '$$' : '$' ) + '  ⟵ ' + e ;
        } ) ;
    } ) ;
    return Promise.all( work ) ;
  }

  /**
   * Draw each smiles: line of {@code root}'s diagrams, as SVG. SvgDrawer
   * is called directly: 2.1.7's Drawer passes its arguments to it out of
   * order and draws nothing.
   */
  function drawSmiles( root ) {

    var boxes = root.querySelectorAll( 'div.smiles' ) ;
    Array.prototype.forEach.call( boxes, function( box ) {
      var smiles = box.getAttribute( 'data-smiles' ) ;
      var svg    = document.createElementNS( SVG_NS, 'svg' ) ;
      svg.setAttribute( 'width', '320' ) ;
      svg.setAttribute( 'height', '220' ) ;
      box.appendChild( svg ) ;
      try {
        SmilesDrawer.parse( smiles, function( tree ) {
          try {
            new SmilesDrawer.SvgDrawer( { width : 320, height : 220 } )
                .draw( tree, svg, 'light', null, false ) ;
          }
          catch( e ) {
            notDrawable( box, svg, e ) ;
          }
        }, function( err ) {
          notDrawable( box, svg, err ) ;
        } ) ;
      }
      catch( e ) {
        notDrawable( box, svg, e ) ;
      }
    } ) ;
  }

  function notDrawable( box, drawing, err ) {
    if( drawing.parentNode ) drawing.parentNode.removeChild( drawing ) ;
    var span = document.createElement( 'div' ) ;
    span.className   = 'bad' ;
    span.textContent = 'SMILES not drawable' +
                       ( err && err.message ? ': ' + err.message : '' ) ;
    box.appendChild( span ) ;
  }

  /**
   * MathJax's SVG stylesheet, once: tex2svgPromise doesn't add it, and
   * without it formulas show twice and errors as black boxes.
   */
  function addMathStyles() {
    if( document.getElementById( 'MJX-SVG-styles' ) ) return ;
    document.head.appendChild( MathJax.svgStylesheet() ) ;
  }

  /** Render {@code markdown} in place of what is shown. */
  window.show = function( markdown ) {

    var gen  = ++generation ;
    var root = document.getElementById( 'content' ) ;

    try {
      var taken = takeMaths( markdown || '' ) ;
      var html  = renderer().render( taken.markdown )
                            .replace( MARK, '<hr class="lct">' )
                            .replace( SLOT, function( all, n ) {
        var m = taken.maths[ +n ] ;
        return '<span class="tex" data-n="' + n + '">' +
               escape( m.display ? '$$' + m.tex + '$$' : '$' + m.tex + '$' ) +
               '</span>' ;
      } ) ;
      root.innerHTML = html ;
      window.scrollTo( 0, 0 ) ;
      drawSmiles( root ) ;
      MathJax.startup.promise.then( function() {
        addMathStyles() ;
        if( gen === generation ) return drawMaths( root, taken.maths, gen ) ;
      } ) ;
    }
    catch( e ) {
      root.innerHTML = '<div id="error">Could not render: ' +
                       escape( String( e ) ) + '</div><pre>' +
                       escape( markdown || '' ) + '</pre>' ;
    }
  } ;
} )() ;
