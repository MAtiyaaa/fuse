import { VFC } from "react";

/**
 * Fuse's mark, drawn from the same paths as the app's: the rounded frame, the fuse line and its
 * spark. It takes the colour of the text around it, so it fits Steam's light and dark menus.
 */
export const FuseMark: VFC<{ size?: number }> = ({ size = 20 }) => (
  <svg width={size} height={size} viewBox="0 0 100 100" fill="none" aria-hidden="true">
    <path
      d="M49.8 5h.4c15.68 0 23.52 0 29.51 3.05a28 28 0 0 1 12.24 12.24C95 26.28 95 34.12 95 49.8v.4c0 15.68 0 23.52-3.05 29.51a28 28 0 0 1-12.24 12.24C73.72 95 65.88 95 50.2 95h-.4c-15.68 0-23.52 0-29.51-3.05A28 28 0 0 1 8.05 79.71C5 73.72 5 65.88 5 50.2v-.4c0-15.68 0-23.52 3.05-29.51A28 28 0 0 1 20.29 8.05C26.28 5 34.12 5 49.8 5Z"
      stroke="currentColor"
      strokeWidth={10}
    />
    <path d="M28 70c14 0 16-36 36-36 3.2 0 5.2-1.5 6-4" stroke="currentColor" strokeWidth={10} strokeLinecap="round" />
    <circle cx={70} cy={30} r={9} fill="#FF6A3D" />
  </svg>
);
