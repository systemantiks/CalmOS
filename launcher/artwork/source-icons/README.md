# HDMI brand logos

Downloaded on 2026-10-20 from [Simple Icons 11.15.0](https://github.com/simple-icons/simple-icons/tree/11.15.0/icons).
The original SVGs are retained here. Simple Icons publishes the artwork under
[CC0 1.0](LICENSE.md); brand names and trademarks remain their owners' property.

| Logo | Download | Brand source recorded by Simple Icons |
|---|---|---|
| PlayStation | [playstation.svg](https://raw.githubusercontent.com/simple-icons/simple-icons/11.15.0/icons/playstation.svg) | [PlayStation](https://www.playstation.com/en-us/) |
| Xbox | [xbox.svg](https://raw.githubusercontent.com/simple-icons/simple-icons/11.15.0/icons/xbox.svg) | [Xbox consoles](https://www.xbox.com/en-US/consoles) |
| Apple | [apple.svg](https://raw.githubusercontent.com/simple-icons/simple-icons/11.15.0/icons/apple.svg) | [Simple Icons source SVG](https://github.com/simple-icons/simple-icons/blob/11.15.0/icons/apple.svg) |
| Nintendo | [nintendo.svg](https://raw.githubusercontent.com/simple-icons/simple-icons/11.15.0/icons/nintendo.svg) | [Nintendo](https://www.nintendo.com) |
| Apple TV | [appletv.svg](https://raw.githubusercontent.com/simple-icons/simple-icons/11.15.0/icons/appletv.svg) | [Apple TV logo](https://en.wikipedia.org/wiki/File:Apple_TV_(logo).svg) |

`../../res/drawable/logo_*.xml` contains Android VectorDrawable versions of the
PlayStation, Xbox, and Apple paths. PlayStation's empty vertical margins are cropped
to `y=2.5..21.5`; Xbox and Apple use the full `0..24` viewport. `IconView` preserves
their aspect ratios and tints them to match the theme.

The Nintendo and Apple TV SVGs are retained as source history. Since Home 1.6,
Nintendo uses the original handheld controller drawing and Apple uses the standalone
symbol. The laptop is a line drawing in `IconView`.
