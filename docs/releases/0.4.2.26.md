# Fuse 0.4.2.26 - The Native Update

An update coming down says how much has come and how fast, and Fuse offers a GitHub token when
GitHub limits its checks.

## New

- When GitHub is limiting update checks from your network, About offers to add a GitHub token
  (the Store's, so both use it). Without one, the update still downloads straight from GitHub's
  website.

## Changed

- A Fuse update downloading shows how much has come and how fast ("78 of 180 MB, 2.1 MB a
  second"), and its percentage moves in tenths, so a slow download no longer looks stuck.
- An update found through GitHub's website (when its API has no checks left) has its size asked
  for before it downloads, so its progress shows from the start.

## Fixed

- An update could sit at 1% for a long time on a slow connection, as if stuck.
