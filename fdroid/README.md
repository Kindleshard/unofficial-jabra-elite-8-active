# Getting the app into F-Droid

F-Droid builds apps from source on its own servers and signs them with its own key, so nothing is uploaded from here. Getting listed is a merge request to F-Droid's metadata repository, reviewed by volunteers (usually a few weeks).

1. Publish this repository on GitHub and tag the release commit `v0.14` (the tag the build recipe points to).
2. Sign in to GitLab and fork <https://gitlab.com/fdroid/fdroiddata>.
3. Add [`io.github.kindleshard.elite8active.yml`](io.github.kindleshard.elite8active.yml) to the fork as `metadata/io.github.kindleshard.elite8active.yml`.
4. Optionally check it locally with `fdroid lint` and `fdroid build -v -l io.github.kindleshard.elite8active` (needs F-Droid's tools).
5. Open a merge request from the fork, using the "App inclusion" template, and answer the reviewers' questions.

What F-Droid builds: the `fdroid` flavor in `android/app`, which uses only free libraries. Its store text and icon come from [`../fastlane/metadata/android/en-US`](../fastlane/metadata/android/en-US). New versions are picked up from new `v*` tags once the app is listed.
