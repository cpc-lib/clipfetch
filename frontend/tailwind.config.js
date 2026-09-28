/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js}'],
  theme: {
    extend: {
      colors: {
        primary: {
          DEFAULT: '#1777FF',
          dark: '#0E5FD8',
          light: '#E8F2FF'
        }
      },
      borderRadius: {
        card: '12px'
      },
      boxShadow: {
        card: '0 2px 12px rgba(15, 42, 84, 0.08)'
      }
    }
  },
  plugins: [require('@tailwindcss/typography')]
}
