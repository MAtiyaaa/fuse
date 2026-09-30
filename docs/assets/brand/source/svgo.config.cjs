// Path data only: relative and shorter commands, numbers to two decimals. Nothing that touches ids,
// gradients, filters or structure.
module.exports = {
  multipass: true,
  js2svg: { pretty: false, finalNewline: true },
  plugins: [{ name: 'convertPathData', params: { floatPrecision: 2, transformPrecision: 3 } }],
};
