/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js}'],
  theme: {
    extend: {
      colors: {
        primary: {
          DEFAULT: '#EC4899',
          dark: '#DB2777',
          light: '#FCE7F3'
        }
      },
      borderRadius: {
        card: '12px'
      },
      boxShadow: {
        card: '0 2px 12px rgba(236, 72, 153, 0.08)'
      }
    }
  },
  plugins: [require('@tailwindcss/typography')]
}
