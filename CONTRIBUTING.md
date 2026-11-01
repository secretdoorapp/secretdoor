# Contributing to SecretDoor

Thanks for helping! SecretDoor is free software (GNU GPL v3) and a fork of the Bitwarden Android
app.

## Before you start

- Read the [documentation](docs/README.md), the [project plan](docs/SECRETDOOR_PLAN.md) and the
  [architecture notes](docs/ARCHITECTURE.md).
- Open an issue to discuss larger changes first. SecretDoor deliberately has no server and no
  account; features that need either will not be accepted.
- Security problems: do not open an issue; follow [SECURITY.md](SECURITY.md).

## Building and testing

See [Building](README.md#building). Run the unit tests with
`./gradlew app:testFdroidDebugUnitTest` (add `-PtestMaxHeapSize=1280m -PtestForkEvery=2
--max-workers=1` on machines with little memory).

## Code

- Follow [docs/STYLE_AND_BEST_PRACTICES.md](docs/STYLE_AND_BEST_PRACTICES.md) (100-character
  lines, KDoc on public APIs).
- Add or update unit tests for what you change.
- New user-facing text goes into `ui/src/main/res/values/strings.xml`; name each string after its
  own text.
- Keep the network guard intact: the app must not connect to anything beyond the two documented
  uses (see [docs/PRIVACY.md](docs/PRIVACY.md)).
- Do not add libraries with licenses that are incompatible with the GPL v3, or proprietary
  libraries. Update [docs/THIRD_PARTY_LICENSES.md](docs/THIRD_PARTY_LICENSES.md) when dependencies
  change.

## License of contributions

By submitting a contribution you agree to license it under the GNU General Public License v3, the
same license as the rest of the project.
