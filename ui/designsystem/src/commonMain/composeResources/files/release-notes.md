# Fuse 0.4.4.1 - The Tune-Up Update

A Fuse One host whose folder another program took over says so, and starts by itself once it is given back.

## New

- Nothing new in this release.

## Changed

- A Fuse One host checks it can use its data folder before it starts. When it can't (another
  program changed the folder's owner, a volume mounted from another user), it says which file,
  who owns it, who the host runs as and the command that gives the folder back, both in its log
  and on every device's Sync page, and starts by itself once the folder is fixed.
- A host that updated itself keeps running the Fuse it has until the new one could open its data
  folder, so an update never turns a hidden folder problem into the host not starting.
- A host that can't start for any other reason tries again a minute later, not every few seconds,
  and a new host service waits longer each time it stops, up to two minutes.

## Fixed

- A host whose admin token it could no longer read failed at every start, a few seconds apart,
  until someone looked: it now waits and explains instead.
- While a host could no longer write to its folder, devices saw "Something other than the host
  answered (500)". They now see what is wrong with the host's folder and how to fix it.
- The host's log named the downloaded file after an update ("installed Fuse-0.4.4-x86_64.AppImage
  to ..."), not the file it replaced. It now names the file Fuse runs from.
