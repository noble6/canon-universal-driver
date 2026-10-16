# Arch packaging

`PKGBUILD` here builds the tagged GitHub release; it is the recipe for the
AUR package `canon-ivec-driver`. (`install/PKGBUILD` builds the local
checkout instead, for development and for the release workflow.)

## Publishing to the AUR

Needs an AUR account with an SSH key (https://aur.archlinux.org/register).

```bash
git clone ssh://aur@aur.archlinux.org/canon-ivec-driver.git aur-canon-ivec-driver
cp PKGBUILD canon-ivec-driver.install aur-canon-ivec-driver/
cd aur-canon-ivec-driver
updpkgsums                         # pin the release tarball checksum
makepkg --printsrcinfo > .SRCINFO
makepkg -si                        # build and test once locally
git add PKGBUILD .SRCINFO canon-ivec-driver.install
git commit -m "canon-ivec-driver 1.3.0"
git push
```

For a new release: bump `pkgver`, reset `pkgrel=1`, then repeat from `updpkgsums`.

## Until then

Every published GitHub release gets an Arch package and a pacman
repository database attached by `.github/workflows/release.yml`; see the
README's install section.
