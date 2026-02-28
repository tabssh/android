# TabSSH Nerd Fonts

This directory should contain Nerd Font files for enhanced terminal display with icons.

## Download Fonts

Download the Regular variant of each font from: https://www.nerdfonts.com/font-downloads

### Required Files

Place the following TTF files in this directory:

| File Name | Download Link |
|-----------|---------------|
| `JetBrainsMonoNerdFont-Regular.ttf` | [JetBrainsMono Nerd Font](https://github.com/ryanoasis/nerd-fonts/releases/latest) |
| `FiraCodeNerdFont-Regular.ttf` | [FiraCode Nerd Font](https://github.com/ryanoasis/nerd-fonts/releases/latest) |
| `HackNerdFont-Regular.ttf` | [Hack Nerd Font](https://github.com/ryanoasis/nerd-fonts/releases/latest) |
| `CascadiaCodeNerdFont-Regular.ttf` | [CaskaydiaCove Nerd Font](https://github.com/ryanoasis/nerd-fonts/releases/latest) |
| `SourceCodeProNerdFont-Regular.ttf` | [SauceCodePro Nerd Font](https://github.com/ryanoasis/nerd-fonts/releases/latest) |
| `MesloLGSNerdFont-Regular.ttf` | [Meslo Nerd Font](https://github.com/ryanoasis/nerd-fonts/releases/latest) |
| `RobotoMonoNerdFont-Regular.ttf` | [RobotoMono Nerd Font](https://github.com/ryanoasis/nerd-fonts/releases/latest) |
| `UbuntuMonoNerdFont-Regular.ttf` | [UbuntuMono Nerd Font](https://github.com/ryanoasis/nerd-fonts/releases/latest) |
| `DejaVuSansMNerdFont-Regular.ttf` | [DejaVuSansMono Nerd Font](https://github.com/ryanoasis/nerd-fonts/releases/latest) |

## Quick Download Script

```bash
# Download and extract fonts
cd /path/to/tabssh/android/app/src/main/assets/fonts

# JetBrains Mono
curl -LO https://github.com/ryanoasis/nerd-fonts/releases/latest/download/JetBrainsMono.zip
unzip JetBrainsMono.zip -d temp && mv temp/JetBrainsMonoNerdFont-Regular.ttf . && rm -rf temp JetBrainsMono.zip

# Fira Code
curl -LO https://github.com/ryanoasis/nerd-fonts/releases/latest/download/FiraCode.zip
unzip FiraCode.zip -d temp && mv temp/FiraCodeNerdFont-Regular.ttf . && rm -rf temp FiraCode.zip

# Hack
curl -LO https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Hack.zip
unzip Hack.zip -d temp && mv temp/HackNerdFont-Regular.ttf . && rm -rf temp Hack.zip

# And so on for other fonts...
```

## Notes

- If fonts are not present, TabSSH will fall back to the system monospace font
- Nerd Fonts include icons for: Powerline, Font Awesome, Devicons, Octicons, and more
- These icons are useful for displaying git status, file types, and other terminal UI elements
- Each font file is approximately 2-5MB

## License

Nerd Fonts are distributed under various open source licenses.
See https://github.com/ryanoasis/nerd-fonts for details.
